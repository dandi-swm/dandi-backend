package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisClient
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.worker.config.ConditionalOnWorkerEnabled
import com.dandi.nyummy.worker.dto.AnalysisPushResult
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
@ConditionalOnWorkerEnabled
class AnalysisService(
    private val nutritionAnalysisClient: NutritionAnalysisClient,
    private val mealAnalysisJobTransactionService: MealAnalysisJobTransactionService,
    private val catRepository: CatRepository,
    private val notificationService: NotificationService,
    private val appLinkProperties: AppLinkProperties,
    private val clock: Clock,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AnalysisService::class.java)
    }

    /**
     * Worker가 lease로 가져간 작업 한 건을 분석하고 결과를 알린다. 실행기 스레드에서 돈다.
     *
     * Gemini 호출은 트랜잭션 밖에서 하고, 결과는 [MealAnalysisJobTransactionService]의 짧은 트랜잭션으로 남긴다.
     * - 성공하면 COMPLETED로 확정한다.
     * - 요청 한도 초과·서버 오류·타임아웃처럼 일시적인 실패는 재시도할 수 있으면 대기로 되돌리고 알리지 않는다.
     * - 그 밖의 실패(재시도를 다 쓴 일시적 실패, 음식 아님, 응답 해석 실패, 거부, 분류에 없는 예외)는 FAILED로 끝낸다.
     *
     * lease가 만료돼 다른 Worker가 같은 작업을 가져갔으면 결과를 쓰지 않고 알리지도 않는다.
     * 분석하는 동안 사용자가 식사를 지웠으면 결과는 남기고 알림만 건너뛴다.
     */
    fun analyzeMealAnalysisJob(job: MealAnalysisJobResult) {
        // 이 시도를 식별하는 값. 재시도가 또 실패해도 앞선 실패와 다른 알림이 되게 한다.
        val analyzedAt = Instant.now(clock)
        var failureCategory: AnalysisFailureCategory? = null

        val pushResult = try {
            val analysisResult = nutritionAnalysisClient.analyzeNutrition(job.imageKey)
            mealAnalysisJobTransactionService.confirmMealAnalysis(job, analysisResult)
        } catch (e: AnalysisFailureException) {
            failureCategory = e.category
            handleAnalysisFailure(job, e)
        } catch (e: RuntimeException) {
            logger.error("영양 분석 중 예상하지 못한 오류로 실패합니다: mealId={}, leaseCount={}", job.mealId, job.leaseCount, e)
            mealAnalysisJobTransactionService.failMealAnalysis(job)
        }

        if (pushResult == null) {
            return
        }
        if (pushResult.isDeleted) {
            logger.info("분석하는 동안 지워진 식사라 알림을 보내지 않습니다: mealId={}", pushResult.mealId)
            return
        }
        sendAnalysisPush(pushResult, failureCategory, analyzedAt)
    }

    /**
     * 분류된 실패를 재시도할지 FAILED로 끝낼지 정한다.
     *
     * @return 알림에 쓸 값. 재시도로 되돌렸거나 이 Worker의 작업이 아니면 null
     */
    private fun handleAnalysisFailure(job: MealAnalysisJobResult, e: AnalysisFailureException): AnalysisPushResult? {
        when (e.category) {
            AnalysisFailureCategory.UNAVAILABLE ->
                if (job.canRetry) {
                    logger.warn(
                        "영양 분석이 일시적으로 실패해 다시 시도합니다: mealId={}, leaseCount={}, {}",
                        job.mealId,
                        job.leaseCount,
                        e.message,
                    )
                    mealAnalysisJobTransactionService.retryMealAnalysis(job)
                    return null
                } else {
                    logger.error("영양 분석이 재시도 후에도 실패했습니다: mealId={}, leaseCount={}", job.mealId, job.leaseCount, e)
                }

            AnalysisFailureCategory.NOT_FOOD ->
                logger.info("음식 사진이 아니라 분석을 실패로 끝냅니다: mealId={}", job.mealId)

            AnalysisFailureCategory.INVALID_RESPONSE, AnalysisFailureCategory.INVALID_REQUEST ->
                logger.error("영양 분석에 실패했습니다: mealId={}, category={}", job.mealId, e.category, e)
        }
        return mealAnalysisJobTransactionService.failMealAnalysis(job)
    }

    /**
     * 분석 결과를 푸시로 알린다. 상태가 이미 커밋된 뒤에 보내므로 발송이 실패해도 결과는 남는다.
     * 수신 거부·토큰 없음은 [NotificationService.sendServicePush]가 조용히 넘긴다.
     */
    private fun sendAnalysisPush(
        pushResult: AnalysisPushResult,
        failureCategory: AnalysisFailureCategory?,
        analyzedAt: Instant,
    ) {
        try {
            val catName = catRepository.findByUserId(pushResult.userId)?.name
                ?: run {
                    logger.error("가입 시 생성되어야 할 고양이가 없습니다: userId={}", pushResult.userId)
                    return
                }

            val result = notificationService.sendServicePush(
                pushResult.userId,
                convertPushMessage(pushResult, failureCategory, catName, analyzedAt),
            )

            notificationService.deleteInvalidDeviceTokens(result.invalidTokens)
        } catch (e: RuntimeException) {
            logger.error("분석 결과 알림 발송 실패: mealId={}, status={}", pushResult.mealId, pushResult.status, e)
        }
    }

    /**
     * 성공·실패에 따라 알림 내용을 고른다. 음식 사진이 아니어서 실패했으면 다시 찍어 달라고 따로 안내한다.
     *
     * 링크는 모두 식사 상세다. 성공은 칼로리를 확인하러, 실패는 재시도하러 같은 화면으로 간다.
     */
    private fun convertPushMessage(
        pushResult: AnalysisPushResult,
        failureCategory: AnalysisFailureCategory?,
        catName: String,
        analyzedAt: Instant,
    ): PushMessage {
        val notificationId = NotificationId.createAnalysisId(pushResult.mealId, pushResult.status, analyzedAt)
        val appLink = appLinkProperties.createMealDetailLink(pushResult.mealId)

        return when {
            pushResult.status == MealStatus.COMPLETED -> PushMessage(
                type = PushType.ANALYSIS_COMPLETED,
                notificationId = notificationId,
                title = "$catName 맛있게 먹었다냥 🐾",
                body = "${pushResult.mealName} 분석이 끝났어요. 칼로리 확인해줘!",
                appLink = appLink,
            )

            failureCategory == AnalysisFailureCategory.NOT_FOOD -> PushMessage(
                type = PushType.ANALYSIS_FAILED,
                notificationId = notificationId,
                title = "음식 사진이 아닌 것 같다냥 🐾",
                body = "음식이 보이게 다시 찍어줄래?",
                appLink = appLink,
            )

            else -> PushMessage(
                type = PushType.ANALYSIS_FAILED,
                notificationId = notificationId,
                title = "사진을 못 알아봤다냥 🐾",
                body = "다시 시도해줄래?",
                appLink = appLink,
            )
        }
    }
}
