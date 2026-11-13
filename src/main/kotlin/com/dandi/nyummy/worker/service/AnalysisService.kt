package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.ai.AnalysisImageClient
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisClient
import com.dandi.nyummy.infra.ai.nutrition.ServingNutritionClient
import com.dandi.nyummy.infra.ai.recognition.MealRecognitionClient
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.meal.calculator.calculateMealFoodNutrition
import com.dandi.nyummy.meal.calculator.calculateTotalNutrition
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.CatCommentMealFoodInput
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.worker.config.ConditionalOnAnalysisWorkerEnabled
import com.dandi.nyummy.worker.dto.AnalysisMealResult
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
    private val analysisImageClient: AnalysisImageClient,
    private val mealRecognitionClient: MealRecognitionClient,
    private val servingNutritionClient: ServingNutritionClient,
    private val catService: CatService,
    private val mealAnalysisJobTransactionService: MealAnalysisJobTransactionService,
    private val mealProperties: MealProperties,
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

        // 분석 시도 한 번의 결과와 걸린 시간(S3 다운로드 + Gemini + 결과 저장). job 태그로 작업 종류를 나눈다.
        private const val ANALYSIS_METER = "nyummy.meal.analysis"

        // 작업을 가져갈 수 있게 된 때부터 끝날 때까지 걸린 시간. 사용자가 결과를 기다린 시간이다.
        // 미루지 않은 작업은 쌓인 때부터이고, 사용자가 음식 목록을 고칠 시간을 기다린 영양 작업은 그 시간이 지난 때부터다.
        // 인식 작업은 식사 기록부터 음식 목록이 준비될 때까지(outcome이 recognized)다.
        private const val ANALYSIS_ELAPSED_METER = "nyummy.meal.analysis.elapsed"

        // 종류가 없는 작업(v1 분석)의 job 태그 값
        private const val V1_JOB = "v1"

        // 분류된 실패가 아닌 예외의 category 태그 값
        private const val UNKNOWN_CATEGORY = "unknown"
    }

    /**
     * Worker가 lease로 가져간 작업 한 건을 작업 종류에 맞게 처리하고 결과를 알린다. 실행기 스레드에서 돈다.
     * 인식 작업은 음식을 인식해 음식 줄을 저장하고, 영양 작업은 음식 줄의 영양을 구해 식사를 완료하고,
     * 종류가 없는 작업은 v1 분석을 한다. 실패를 다루는 규칙은 종류와 상관없이 같다.
     *
     * Gemini 호출은 트랜잭션 밖에서 하고, 결과는 [MealAnalysisJobTransactionService]의 짧은 트랜잭션으로 남긴다.
     * - 성공하면 COMPLETED로 확정하고 알린다. 인식 작업은 음식 줄을 저장하고 영양 작업으로 넘기며 알리지 않는다.
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
            when (job.category) {
                MealAnalysisJobCategory.RECOGNITION -> recognizeMeal(job, analyzedAt)
                MealAnalysisJobCategory.NUTRITION -> analyzeMealFoods(job, analyzedAt)
                null -> analyzeNutrition(job, analyzedAt)
            }
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

    /** 사진 한 장에서 이름·영양·코멘트를 한 번에 구해 COMPLETED로 확정하고 알린다(v1 분석). */
    private fun analyzeNutrition(job: MealAnalysisJobResult, analyzedAt: Instant) {
        val analysisResult = nutritionAnalysisClient.analyzeNutrition(job.imageKey, job.language)
        val pushResult = mealAnalysisJobTransactionService.confirmMealAnalysis(job, analysisResult)
        if (pushResult != null) {
            recordAnalysis(job, AnalysisOutcome.COMPLETED, null, analyzedAt)
        }
        sendAnalysisPush(pushResult, null, analyzedAt, job.language)
    }

    /**
     * 식사 기록 요청 안에서 끝내지 못한 음식 인식을 한다. 사진을 받아 인식하고, 음식 줄을 저장한 뒤 영양 작업으로 넘긴다.
     *
     * 인식이 끝났다는 알림은 보내지 않는다. 사용자는 대기 화면에서 상태를 조회하다 음식 목록으로 넘어간다.
     * 음식이 아니거나 인식에 실패한 것은 [handleAnalysisFailure]가 다른 작업과 같은 규칙으로 다루고 알린다.
     */
    private fun recognizeMeal(job: MealAnalysisJobResult, analyzedAt: Instant) {
        val image = analysisImageClient.downloadImage(job.imageKey)
        val contentType = image.contentType
            ?: throw AnalysisFailureException(
                AnalysisFailureCategory.INVALID_REQUEST,
                "인식할 이미지의 콘텐츠 타입이 없습니다: ${job.imageKey}",
            )

        val recognition = mealRecognitionClient.recognizeMeal(image.bytes, contentType, job.language)
        val isConfirmed = mealAnalysisJobTransactionService.confirmMealRecognition(
            job = job,
            recognition = recognition,
            nutritionDelay = mealProperties.recognition.nutritionDelay,
        )
        if (isConfirmed) {
            recordAnalysis(job, AnalysisOutcome.RECOGNIZED, null, analyzedAt)
        }
    }

    /**
     * 음식 줄의 영양을 구해 식사를 COMPLETED로 확정하고 알린다. 인식한 뒤 기다린 시간이 지났거나 사용자가 음식 목록을 보냈을 때 돈다.
     *
     * 1인분 조회와 코멘트는 트랜잭션 밖에서 하고, 결과는 한 트랜잭션으로 저장한다. 그래서 COMPLETED인 식사에는 늘 코멘트가 있다.
     * 코멘트는 식사에 아직 없을 때만 만든다. 만들지 못하면 [CatService.createCatComment]가 고정 문구를 돌려주므로
     * 코멘트 때문에 영양 작업이 실패하지는 않는다.
     */
    private fun analyzeMealFoods(job: MealAnalysisJobResult, analyzedAt: Instant) {
        val meal = mealAnalysisJobTransactionService.getAnalysisMeal(job)
            ?: run {
                logger.error("영양 작업의 식사가 없습니다: jobId={}, mealId={}", job.jobId, job.mealId)
                return
            }
        if (meal.mealFoods.isEmpty()) {
            throw AnalysisFailureException(
                AnalysisFailureCategory.INVALID_REQUEST,
                "영양을 계산할 음식 줄이 없습니다: mealId=${job.mealId}",
            )
        }

        val servingNutritions = getServingNutritions(meal)
        val catComment = if (meal.hasCatComment) null else createCatComment(meal, servingNutritions, job.language)

        val pushResult = mealAnalysisJobTransactionService.confirmMealNutrition(job, servingNutritions, catComment)
        if (pushResult != null) {
            recordAnalysis(job, AnalysisOutcome.COMPLETED, null, analyzedAt)
        }
        sendAnalysisPush(pushResult, null, analyzedAt, job.language)
    }

    /**
     * 1인분 영양이나 아이콘이 비어 있는 줄만 모아 한 번에 조회한다. 비어 있는 줄이 없으면 모델을 부르지 않는다.
     * 인분만 바꾼 식사나 사용자가 영양과 아이콘을 모두 정한 식사가 그렇다.
     *
     * @return 음식 줄 ID별 조회 결과. 조회하지 않은 줄은 없다
     */
    private fun getServingNutritions(meal: AnalysisMealResult): Map<Long, ServingNutritionResult> {
        val mealFoods = meal.mealFoods.filter { it.servingNutrition == null || it.iconId == null }
        if (mealFoods.isEmpty()) {
            return emptyMap()
        }
        val servingNutritionResults =
            servingNutritionClient.getServingNutritions(mealFoods.map { it.name }, meal.inputCategory)

        return mealFoods.map { it.mealFoodId }.zip(servingNutritionResults).toMap()
    }

    /**
     * 저장하기 전의 값으로 줄 영양과 식사 합계를 먼저 구해 코멘트를 만든다.
     * 줄에 이미 1인분 영양이 있으면 그 값을, 없으면 방금 조회한 값을 쓴다. 저장할 때 채우는 규칙과 같다.
     */
    private fun createCatComment(
        meal: AnalysisMealResult,
        servingNutritions: Map<Long, ServingNutritionResult>,
        language: Language,
    ): String {
        val servings = meal.mealFoods.map { mealFood ->
            val servingNutrition = mealFood.servingNutrition
                ?: servingNutritions.getValue(mealFood.mealFoodId).servingNutrition
            servingNutrition to mealFood.portion
        }

        return catService.createCatComment(
            userId = meal.userId,
            mealAt = meal.mealAt,
            mealFoods = meal.mealFoods.zip(servings) { mealFood, (servingNutrition, portion) ->
                CatCommentMealFoodInput(
                    name = mealFood.name,
                    portion = portion,
                    nutrition = calculateMealFoodNutrition(servingNutrition, portion),
                )
            },
            nutrition = calculateTotalNutrition(servings),
            language = language,
        )
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
     * 작업이 끝났으면(COMPLETED·RECOGNIZED·FAILED) 작업을 가져갈 수 있게 된 때부터 걸린 시간도 기록한다.
     */
    private fun recordAnalysis(
        job: MealAnalysisJobResult,
        outcome: AnalysisOutcome,
        category: String,
        analyzedAt: Instant,
    ) {
        val now = Instant.now(clock)
        val jobTag = job.category?.name?.lowercase() ?: V1_JOB
        Timer.builder(ANALYSIS_METER)
            .tag("job", jobTag)
            .tag("outcome", outcome.tagValue)
            .tag("category", category)
            .register(meterRegistry)
            .record(Duration.between(analyzedAt, now))

        if (outcome.isFinal) {
            Timer.builder(ANALYSIS_ELAPSED_METER)
                .tag("job", jobTag)
                .tag("outcome", outcome.tagValue)
                .register(meterRegistry)
                .record(Duration.between(job.availableAt, now))
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
 * @property isFinal 작업이 끝났는지. 재시도·미룸은 작업이 큐에 남아 아직 끝나지 않았다
 */
private enum class AnalysisOutcome(val tagValue: String, val isFinal: Boolean) {
    COMPLETED("completed", true),

    // 인식 작업이 음식 줄을 저장하고 끝났다. 식사는 영양 작업을 기다린다.
    RECOGNIZED("recognized", true),
    RETRIED("retried", false),
    DELAYED("delayed", false),
    FAILED("failed", true),
}
