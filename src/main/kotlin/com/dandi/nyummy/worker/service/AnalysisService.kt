package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisClient
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.worker.config.ConditionalOnAnalysisWorkerEnabled
import com.dandi.nyummy.worker.dto.AnalysisPushResult
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
@ConditionalOnAnalysisWorkerEnabled
class AnalysisService(
    private val nutritionAnalysisClient: NutritionAnalysisClient,
    private val mealAnalysisJobTransactionService: MealAnalysisJobTransactionService,
    private val catRepository: CatRepository,
    private val notificationService: NotificationService,
    private val appLinkProperties: AppLinkProperties,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    private val messageSource: MessageSource,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AnalysisService::class.java)

        // 설정 오류로 되돌린 작업을 다시 가져가기까지 기다리는 시간. 설정이 고장 난 동안 Gemini를 1초마다 부르지 않게 한다.
        private val MISCONFIGURED_DELAY = Duration.ofMinutes(1)

        // 분석 시도 한 번의 결과와 걸린 시간(S3 다운로드 + Gemini + 결과 저장).
        private const val ANALYSIS_METER = "nyummy.meal.analysis"

        // 작업이 쌓인 때부터 COMPLETED·FAILED로 끝날 때까지 걸린 시간. 사용자가 결과를 기다린 시간이다.
        private const val ANALYSIS_ELAPSED_METER = "nyummy.meal.analysis.elapsed"

        // 분류된 실패가 아닌 예외의 category 태그 값
        private const val UNKNOWN_CATEGORY = "unknown"
    }

    /**
     * Worker가 lease로 가져간 작업 한 건을 분석하고 결과를 알린다. 실행기 스레드에서 돈다.
     *
     * Gemini 호출은 트랜잭션 밖에서 하고, 결과는 [MealAnalysisJobTransactionService]의 짧은 트랜잭션으로 남긴다.
     * - 성공하면 COMPLETED로 확정한다.
     * - 요청 한도 초과·서버 오류·타임아웃처럼 일시적인 실패는 재시도할 수 있으면 대기로 되돌리고 알리지 않는다.
     * - 키·권한 같은 설정 오류는 시도 횟수를 쓰지 않고 잠시 뒤로 되돌리고 알리지 않는다. 설정을 고치면 그대로 처리된다.
     * - 그 밖의 실패(재시도를 다 쓴 일시적 실패, 음식 아님, 응답 해석 실패, 거부, 분류에 없는 예외)는 FAILED로 끝낸다.
     *
     * lease가 만료돼 다른 Worker가 같은 작업을 가져갔으면 결과를 쓰지 않고 알리지도 않는다.
     * 분석하는 동안 사용자가 식사를 지웠으면 결과는 남기고 알림만 건너뛴다.
     */
    fun analyzeMealAnalysisJob(job: MealAnalysisJobResult) {
        // 이 시도를 식별하는 값. 재시도가 또 실패해도 앞선 실패와 다른 알림이 되게 한다.
        val analyzedAt = Instant.now(clock)

        try {
            val analysisResult = nutritionAnalysisClient.analyzeNutrition(job.imageKey)
            val pushResult = mealAnalysisJobTransactionService.confirmMealAnalysis(job, analysisResult)
            if (pushResult != null) {
                recordAnalysis(job, AnalysisOutcome.COMPLETED, null, analyzedAt)
            }
            sendAnalysisPush(pushResult, null, analyzedAt, job.language)
        } catch (e: AnalysisFailureException) {
            handleAnalysisFailure(job, e, analyzedAt)
        } catch (e: RuntimeException) {
            logger.error("영양 분석 중 예상하지 못한 오류로 실패합니다: mealId={}, leaseCount={}", job.mealId, job.leaseCount, e)
            val pushResult = mealAnalysisJobTransactionService.failMealAnalysis(job)
            if (pushResult != null) {
                recordAnalysis(job, AnalysisOutcome.FAILED, UNKNOWN_CATEGORY, analyzedAt)
            }
            sendAnalysisPush(pushResult, null, analyzedAt, job.language)
        }
    }

    /**
     * 분류된 실패를 재시도할지 FAILED로 끝낼지 정하고, FAILED로 끝냈으면 알린다.
     *
     * 일시적인 실패(UNAVAILABLE)는 재시도할 수 있으면 대기로 되돌리고 알리지 않는다. 마지막 시도였으면 FAILED로 끝낸다.
     * 설정 오류(MISCONFIGURED)는 사용자 탓이 아니고 모든 작업이 같이 실패하므로, FAILED로 끝내지도 알리지도 않고
     * 번호를 내려 [MISCONFIGURED_DELAY] 뒤에 다시 가져가게 한다.
     * Worker가 종료될 때 대기 시간 안에 끝나지 않은 작업도 같은 규칙으로 정리한다.
     */
    fun handleAnalysisFailure(job: MealAnalysisJobResult, failure: AnalysisFailureException) {
        handleAnalysisFailure(job, failure, Instant.now(clock))
    }

    // 기본 인자로 시각을 받지 않는다. Kotlin은 기본 인자 호출을 정적 보조 메서드로 바꿔 mock으로 가로챌 수 없게 된다.
    private fun handleAnalysisFailure(
        job: MealAnalysisJobResult,
        failure: AnalysisFailureException,
        analyzedAt: Instant,
    ) {
        when (failure.category) {
            AnalysisFailureCategory.UNAVAILABLE ->
                if (job.canRetry) {
                    logger.warn(
                        "영양 분석이 일시적으로 실패해 다시 시도합니다: mealId={}, leaseCount={}, {}",
                        job.mealId,
                        job.leaseCount,
                        failure.message,
                    )
                    if (mealAnalysisJobTransactionService.retryMealAnalysis(job)) {
                        recordAnalysis(job, AnalysisOutcome.RETRIED, failure.category, analyzedAt)
                    }
                    return
                } else {
                    logger.error("영양 분석이 재시도 후에도 실패했습니다: mealId={}, leaseCount={}", job.mealId, job.leaseCount, failure)
                }

            AnalysisFailureCategory.NOT_FOOD ->
                logger.info("음식 사진이 아니라 분석을 실패로 끝냅니다: mealId={}", job.mealId)

            AnalysisFailureCategory.INVALID_RESPONSE, AnalysisFailureCategory.INVALID_REQUEST ->
                logger.error("영양 분석에 실패했습니다: mealId={}, category={}", job.mealId, failure.category, failure)

            AnalysisFailureCategory.MISCONFIGURED -> {
                logger.error(
                    "설정 오류로 영양 분석을 하지 못해 잠시 뒤 다시 시도합니다: mealId={}, leaseCount={}",
                    job.mealId,
                    job.leaseCount,
                    failure,
                )
                if (mealAnalysisJobTransactionService.cancelMealAnalysis(job, MISCONFIGURED_DELAY)) {
                    recordAnalysis(job, AnalysisOutcome.DELAYED, failure.category, analyzedAt)
                }
                return
            }
        }
        val pushResult = mealAnalysisJobTransactionService.failMealAnalysis(job)
        if (pushResult != null) {
            recordAnalysis(job, AnalysisOutcome.FAILED, failure.category, analyzedAt)
        }
        sendAnalysisPush(pushResult, failure.category, analyzedAt, job.language)
    }

    private fun recordAnalysis(
        job: MealAnalysisJobResult,
        outcome: AnalysisOutcome,
        category: AnalysisFailureCategory?,
        analyzedAt: Instant,
    ) {
        recordAnalysis(job, outcome, category?.name?.lowercase() ?: "none", analyzedAt)
    }

    /**
     * 이 Worker가 결과를 실제로 남긴 시도만 기록한다. 다른 Worker가 가져가 아무것도 쓰지 못한 시도는 세지 않는다.
     * COMPLETED·FAILED로 끝났으면 작업이 쌓인 때부터 걸린 시간도 기록한다.
     */
    private fun recordAnalysis(
        job: MealAnalysisJobResult,
        outcome: AnalysisOutcome,
        category: String,
        analyzedAt: Instant,
    ) {
        val now = Instant.now(clock)
        Timer.builder(ANALYSIS_METER)
            .tag("outcome", outcome.tagValue)
            .tag("category", category)
            .register(meterRegistry)
            .record(Duration.between(analyzedAt, now))

        if (outcome.isFinal) {
            Timer.builder(ANALYSIS_ELAPSED_METER)
                .tag("outcome", outcome.tagValue)
                .register(meterRegistry)
                .record(Duration.between(job.createdAt, now))
        }
    }

    /**
     * 분석 결과를 푸시로 알린다. 상태가 이미 커밋된 뒤에 보내므로 발송이 실패해도 결과는 남는다.
     * 수신 거부·토큰 없음은 [NotificationService.sendServicePush]가 조용히 넘긴다.
     */
    private fun sendAnalysisPush(
        pushResult: AnalysisPushResult?,
        failureCategory: AnalysisFailureCategory?,
        analyzedAt: Instant,
        language: Language,
    ) {
        // 이 Worker의 작업이 아니었으면(다른 Worker가 가져갔으면) 결과를 쓰지 않았으므로 알리지도 않는다.
        if (pushResult == null) {
            return
        }
        if (pushResult.isDeleted) {
            logger.info("분석하는 동안 지워진 식사라 알림을 보내지 않습니다: mealId={}", pushResult.mealId)
            return
        }

        try {
            val catName = catRepository.findByUserId(pushResult.userId)?.name
                ?: run {
                    logger.error("가입 시 생성되어야 할 고양이가 없습니다: userId={}", pushResult.userId)
                    return
                }

            val result = notificationService.sendServicePush(
                pushResult.userId,
                convertPushMessage(pushResult, failureCategory, catName, analyzedAt, language),
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
     * 문구는 식사를 기록한 요청의 언어로 고른다.
     */
    private fun convertPushMessage(
        pushResult: AnalysisPushResult,
        failureCategory: AnalysisFailureCategory?,
        catName: String,
        analyzedAt: Instant,
        language: Language,
    ): PushMessage {
        val notificationId = NotificationId.createAnalysisId(pushResult.mealId, pushResult.status, analyzedAt)
        val appLink = appLinkProperties.createMealDetailLink(pushResult.mealId)

        return when {
            pushResult.status == MealStatus.COMPLETED -> PushMessage(
                userId = pushResult.userId,
                type = PushType.ANALYSIS_COMPLETED,
                notificationId = notificationId,
                title = messageSource.getMessage("push.analysis.completed.title", language, catName),
                body = messageSource.getMessage("push.analysis.completed.body", language, pushResult.mealName),
                appLink = appLink,
            )

            failureCategory == AnalysisFailureCategory.NOT_FOOD -> PushMessage(
                userId = pushResult.userId,
                type = PushType.ANALYSIS_FAILED,
                notificationId = notificationId,
                title = messageSource.getMessage("push.analysis.notFood.title", language),
                body = messageSource.getMessage("push.analysis.notFood.body", language),
                appLink = appLink,
            )

            // 재시도까지 다 실패한 일시적인 실패. 사진 탓이 아니므로 잠시 뒤에 다시 하라고 알린다.
            failureCategory == AnalysisFailureCategory.UNAVAILABLE -> PushMessage(
                userId = pushResult.userId,
                type = PushType.ANALYSIS_FAILED,
                notificationId = notificationId,
                title = messageSource.getMessage("push.analysis.unavailable.title", language),
                body = messageSource.getMessage("push.analysis.unavailable.body", language),
                appLink = appLink,
            )

            else -> PushMessage(
                userId = pushResult.userId,
                type = PushType.ANALYSIS_FAILED,
                notificationId = notificationId,
                title = messageSource.getMessage("push.analysis.failed.title", language),
                body = messageSource.getMessage("push.analysis.failed.body", language),
                appLink = appLink,
            )
        }
    }
}

/**
 * 분석 시도 한 번이 어떻게 끝났는지. 지표의 outcome 태그 값이다.
 *
 * @property isFinal 식사가 COMPLETED·FAILED로 끝났는지. 재시도·미룸은 작업이 큐에 남아 아직 끝나지 않았다
 */
private enum class AnalysisOutcome(val tagValue: String, val isFinal: Boolean) {
    COMPLETED("completed", true),
    RETRIED("retried", false),
    DELAYED("delayed", false),
    FAILED("failed", true),
}
