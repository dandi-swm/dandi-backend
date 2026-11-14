package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.entity.Cat
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
import com.dandi.nyummy.infra.push.dto.PushResult
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.infra.storage.s3.dto.S3ObjectContentResult
import com.dandi.nyummy.language.createTestMessageSource
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.CatCommentMealFoodInput
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.NutritionAnalysisResult
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.worker.dto.AnalysisMealFoodResult
import com.dandi.nyummy.worker.dto.AnalysisMealResult
import com.dandi.nyummy.worker.dto.AnalysisPushResult
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.QueryTimeoutException
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * analyzeMealAnalysisJob이 분석 결과와 실패 분류에 따라 확정·재시도·실패를 고르고, 알맞은 알림을 보내는지 다룬다.
 * 인식 작업은 음식을 인식해 넘기고 알리지 않는지, 영양 작업은 필요한 줄만 조회하고 코멘트를 한 번만 만들어 완료하는지,
 * 실패는 다른 작업과 같은 규칙으로 다루는지도 다룬다.
 * 트랜잭션 안의 동작(fencing·상태 변경)은 MealAnalysisJobTransactionServiceTest가 맡는다.
 */
class AnalysisServiceTest {

    private val now = Instant.parse("2026-11-02T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userId = 1L
    private val mealId = 10L

    private val nutritionDelay = Duration.ofMinutes(5)

    private val nutritionAnalysisClient = mockk<NutritionAnalysisClient>()
    private val analysisImageClient = mockk<AnalysisImageClient>()
    private val mealRecognitionClient = mockk<MealRecognitionClient>()
    private val servingNutritionClient = mockk<ServingNutritionClient>()
    private val catService = mockk<CatService>()
    private val mealAnalysisJobTransactionService = mockk<MealAnalysisJobTransactionService>()
    private val mealProperties = mockk<MealProperties> {
        every { recognition.nutritionDelay } returns nutritionDelay
    }
    private val catRepository = mockk<CatRepository>()
    private val notificationService = mockk<NotificationService>()
    private val appLinkProperties = AppLinkProperties(
        baseUrl = "https://link.test.com",
        homeUrl = "/home",
        signupUrl = "/signup",
        mealDetailUrl = "/meals",
        onboardingUrl = "/onboarding",
        inquiryDetailUrl = "/inquiries",
    )

    private val meterRegistry = SimpleMeterRegistry()

    private val messageSource = createTestMessageSource()

    private val analysisService = AnalysisService(
        nutritionAnalysisClient = nutritionAnalysisClient,
        analysisImageClient = analysisImageClient,
        mealRecognitionClient = mealRecognitionClient,
        servingNutritionClient = servingNutritionClient,
        catService = catService,
        mealAnalysisJobTransactionService = mealAnalysisJobTransactionService,
        mealProperties = mealProperties,
        catRepository = catRepository,
        notificationService = notificationService,
        appLinkProperties = appLinkProperties,
        meterRegistry = meterRegistry,
        clock = clock,
        messageSource = messageSource,
    )

    private val retryableJob = createJob(canRetry = true)
    private val lastJob = createJob(canRetry = false)
    private val analysisResult = NutritionAnalysisResult(
        name = "김치찌개",
        nutrition = Nutrition(calory = 450, carbs = 40, protein = 25, fat = 20),
        catComment = "단백질이 든든하다냥",
        iconId = 12L,
    )
    private val sentMessage = slot<PushMessage>()

    init {
        every { catRepository.findByUserId(userId) } returns Cat(name = "나비", userId = userId)
        every { notificationService.sendServicePush(userId, capture(sentMessage)) } returns
            PushResult(successCount = 1, invalidTokens = emptyList(), retryableTokens = emptyList())
        every { notificationService.deleteInvalidDeviceTokens(any()) } just Runs
        every { mealAnalysisJobTransactionService.confirmMealAnalysis(any(), any()) } returns
            createPushResult(MealStatus.COMPLETED)
        every { mealAnalysisJobTransactionService.failMealAnalysis(any()) } returns createPushResult(MealStatus.FAILED)
        every { mealAnalysisJobTransactionService.retryMealAnalysis(any()) } returns true
        every { mealAnalysisJobTransactionService.cancelMealAnalysis(any(), any()) } returns true
    }

    private fun createJob(canRetry: Boolean) = MealAnalysisJobResult(
        jobId = 1L,
        mealId = mealId,
        leaseCount = if (canRetry) 1 else 2,
        imageKey = "meals/$userId/meal-$mealId.jpg",
        canRetry = canRetry,
        availableAt = now.minusSeconds(30),
        language = Language.KO,
    )

    private fun createPushResult(status: MealStatus, isDeleted: Boolean = false) =
        AnalysisPushResult(mealId = mealId, userId = userId, mealName = "김치찌개", status = status, isDeleted = isDeleted)

    private fun givenAnalysisSuccess(job: MealAnalysisJobResult) {
        every { nutritionAnalysisClient.analyzeNutrition(job.imageKey, job.language) } returns analysisResult
    }

    private fun countAnalysis(outcome: String, category: String): Long =
        meterRegistry.find("nyummy.meal.analysis").tags("outcome", outcome, "category", category).timer()?.count() ?: 0

    private fun findElapsed(outcome: String) =
        meterRegistry.find("nyummy.meal.analysis.elapsed").tags("outcome", outcome).timer()

    // outcome과 상관없이 기록→결과 시간이 하나라도 남았는지
    private fun existsElapsed(): Boolean = meterRegistry.find("nyummy.meal.analysis.elapsed").timers().isNotEmpty()

    private fun givenAnalysisFailure(job: MealAnalysisJobResult, category: AnalysisFailureCategory) {
        every { nutritionAnalysisClient.analyzeNutrition(job.imageKey, job.language) } throws
            AnalysisFailureException(category, "실패")
    }

    private fun assertFailedPushSent(title: String, body: String) {
        assertEquals(PushType.ANALYSIS_FAILED, sentMessage.captured.type)
        assertEquals(title, sentMessage.captured.title)
        assertEquals(body, sentMessage.captured.body)
    }

    // 성공

    @Test
    fun `완료 알림은 식사를 기록한 요청의 언어로 보낸다`() {
        val japaneseJob = retryableJob.copy(language = Language.JA)
        givenAnalysisSuccess(japaneseJob)

        analysisService.analyzeMealAnalysisJob(japaneseJob)

        assertEquals(
            messageSource.getMessage("push.analysis.completed.title", Language.JA, "나비"),
            sentMessage.captured.title,
        )
        assertEquals(
            messageSource.getMessage("push.analysis.completed.body", Language.JA, "김치찌개"),
            sentMessage.captured.body,
        )
    }

    @Test
    fun `분석에 성공하면 결과를 확정하고 완료 알림을 보낸다`() {
        givenAnalysisSuccess(retryableJob)

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.confirmMealAnalysis(retryableJob, analysisResult) }
        assertEquals(
            PushMessage(
                userId = userId,
                type = PushType.ANALYSIS_COMPLETED,
                notificationId = NotificationId.createAnalysisId(mealId, MealStatus.COMPLETED, now),
                title = "나비 맛있게 먹었다냥 🐾",
                body = "김치찌개 분석이 끝났어요. 칼로리 확인해줘!",
                appLink = "https://link.test.com/meals/$mealId",
            ),
            sentMessage.captured,
        )
    }

    @Test
    fun `다른 Worker가 가져간 작업이라 확정하지 못했으면 알림을 보내지 않는다`() {
        givenAnalysisSuccess(retryableJob)
        every { mealAnalysisJobTransactionService.confirmMealAnalysis(any(), any()) } returns null

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    @Test
    fun `분석하는 동안 식사가 지워졌으면 알림을 보내지 않는다`() {
        givenAnalysisSuccess(retryableJob)
        every { mealAnalysisJobTransactionService.confirmMealAnalysis(any(), any()) } returns
            createPushResult(MealStatus.COMPLETED, isDeleted = true)

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    // 분류된 실패

    @Test
    fun `일시적인 실패이고 다시 시도할 수 있으면 대기로 되돌리고 실패로 끝내거나 알리지 않는다`() {
        givenAnalysisFailure(retryableJob, AnalysisFailureCategory.UNAVAILABLE)

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.retryMealAnalysis(retryableJob) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.failMealAnalysis(any()) }
        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    @Test
    fun `일시적인 실패가 마지막 시도면 실패로 끝내고 잠시 뒤에 다시 하라고 알린다`() {
        givenAnalysisFailure(lastJob, AnalysisFailureCategory.UNAVAILABLE)

        analysisService.analyzeMealAnalysisJob(lastJob)

        verify(exactly = 0) { mealAnalysisJobTransactionService.retryMealAnalysis(any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(lastJob) }
        assertFailedPushSent(title = "지금은 소화가 안 된다냥 🐾", body = "잠시 뒤에 다시 시도해줄래?")
    }

    @Test
    fun `종료 정리처럼 실패 처리를 바로 부르면 같은 규칙으로 마지막 시도는 실패로 끝내고 알린다`() {
        analysisService.handleAnalysisFailure(
            lastJob,
            AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "종료"),
        )

        verify(exactly = 0) { nutritionAnalysisClient.analyzeNutrition(any(), any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(lastJob) }
        assertFailedPushSent(title = "지금은 소화가 안 된다냥 🐾", body = "잠시 뒤에 다시 시도해줄래?")
    }

    @Test
    fun `음식 사진이 아니면 다시 시도할 수 있어도 실패로 끝내고 다시 찍어 달라고 알린다`() {
        givenAnalysisFailure(retryableJob, AnalysisFailureCategory.NOT_FOOD)

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 0) { mealAnalysisJobTransactionService.retryMealAnalysis(any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(retryableJob) }
        assertFailedPushSent(title = "음식 사진이 아닌 것 같다냥 🐾", body = "음식이 보이게 다시 찍어줄래?")
    }

    @Test
    fun `응답을 해석하지 못했거나 요청이 거부됐으면 실패로 끝내고 실패 알림을 보낸다`() {
        listOf(AnalysisFailureCategory.INVALID_RESPONSE, AnalysisFailureCategory.INVALID_REQUEST).forEach { category ->
            givenAnalysisFailure(retryableJob, category)

            analysisService.analyzeMealAnalysisJob(retryableJob)

            assertFailedPushSent(title = "사진을 못 알아봤다냥 🐾", body = "다시 시도해줄래?")
        }
        verify(exactly = 0) { mealAnalysisJobTransactionService.retryMealAnalysis(any()) }
        verify(exactly = 2) { mealAnalysisJobTransactionService.failMealAnalysis(retryableJob) }
    }

    @Test
    fun `설정 오류면 마지막 시도라도 실패로 끝내거나 알리지 않고 번호를 내려 1분 뒤로 되돌린다`() {
        givenAnalysisFailure(lastJob, AnalysisFailureCategory.MISCONFIGURED)

        analysisService.analyzeMealAnalysisJob(lastJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.cancelMealAnalysis(lastJob, Duration.ofMinutes(1)) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.retryMealAnalysis(any()) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.failMealAnalysis(any()) }
        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    @Test
    fun `실패로 끝내려 했지만 다른 Worker가 가져간 작업이면 알림을 보내지 않는다`() {
        givenAnalysisFailure(lastJob, AnalysisFailureCategory.INVALID_RESPONSE)
        every { mealAnalysisJobTransactionService.failMealAnalysis(any()) } returns null

        analysisService.analyzeMealAnalysisJob(lastJob)

        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    // 분류에 없는 예외

    @Test
    fun `분석 중 분류에 없는 예외가 나면 다시 시도할 수 있어도 실패로 끝내고 실패 알림을 보낸다`() {
        every { nutritionAnalysisClient.analyzeNutrition(retryableJob.imageKey, retryableJob.language) } throws
            IllegalStateException("예상 밖")

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 0) { mealAnalysisJobTransactionService.retryMealAnalysis(any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(retryableJob) }
        assertFailedPushSent(title = "사진을 못 알아봤다냥 🐾", body = "다시 시도해줄래?")
    }

    @Test
    fun `결과 확정 트랜잭션이 실패하면 실패로 끝낸다`() {
        givenAnalysisSuccess(retryableJob)
        every { mealAnalysisJobTransactionService.confirmMealAnalysis(any(), any()) } throws
            QueryTimeoutException("timeout")

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(retryableJob) }
    }

    // 알림

    @Test
    fun `알림 발송이 실패해도 예외를 밖으로 던지지 않는다`() {
        givenAnalysisSuccess(retryableJob)
        every { notificationService.sendServicePush(any(), any()) } throws IllegalStateException("FCM 오류")

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.confirmMealAnalysis(any(), any()) }
    }

    @Test
    fun `고양이가 없으면 알림을 보내지 않는다`() {
        givenAnalysisSuccess(retryableJob)
        every { catRepository.findByUserId(userId) } returns null

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    @Test
    fun `알림을 보낸 뒤 무효한 기기 토큰을 지운다`() {
        givenAnalysisSuccess(retryableJob)
        every { notificationService.sendServicePush(any(), any()) } returns
            PushResult(successCount = 0, invalidTokens = listOf("dead-token"), retryableTokens = emptyList())

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 1) { notificationService.deleteInvalidDeviceTokens(listOf("dead-token")) }
    }

    // 지표

    @Test
    fun `분석에 성공하면 completed로 세고 작업이 쌓인 때부터 걸린 시간을 남긴다`() {
        givenAnalysisSuccess(retryableJob)

        analysisService.analyzeMealAnalysisJob(retryableJob)

        assertEquals(1, countAnalysis("completed", "none"))
        assertEquals(30.0, findElapsed("completed")?.totalTime(TimeUnit.SECONDS))
    }

    @Test
    fun `일시적인 실패로 다시 시도하면 retried로 세고 끝난 것이 아니라 걸린 시간은 남기지 않는다`() {
        givenAnalysisFailure(retryableJob, AnalysisFailureCategory.UNAVAILABLE)

        analysisService.analyzeMealAnalysisJob(retryableJob)

        assertEquals(1, countAnalysis("retried", "unavailable"))
        assertFalse(existsElapsed())
    }

    @Test
    fun `설정 오류로 미루면 delayed로 세고 걸린 시간은 남기지 않는다`() {
        givenAnalysisFailure(lastJob, AnalysisFailureCategory.MISCONFIGURED)

        analysisService.analyzeMealAnalysisJob(lastJob)

        assertEquals(1, countAnalysis("delayed", "misconfigured"))
        assertFalse(existsElapsed())
    }

    @Test
    fun `실패로 끝나면 failed와 분류로 세고 걸린 시간을 남긴다`() {
        givenAnalysisFailure(retryableJob, AnalysisFailureCategory.NOT_FOOD)

        analysisService.analyzeMealAnalysisJob(retryableJob)

        assertEquals(1, countAnalysis("failed", "not_food"))
        assertEquals(30.0, findElapsed("failed")?.totalTime(TimeUnit.SECONDS))
    }

    @Test
    fun `분류에 없는 예외로 실패하면 unknown으로 센다`() {
        every { nutritionAnalysisClient.analyzeNutrition(retryableJob.imageKey, retryableJob.language) } throws
            IllegalStateException("예상 밖")

        analysisService.analyzeMealAnalysisJob(retryableJob)

        assertEquals(1, countAnalysis("failed", "unknown"))
    }

    @Test
    fun `다른 Worker가 가져가 결과를 쓰지 못했으면 아무것도 세지 않는다`() {
        givenAnalysisSuccess(retryableJob)
        every { mealAnalysisJobTransactionService.confirmMealAnalysis(any(), any()) } returns null

        analysisService.analyzeMealAnalysisJob(retryableJob)

        assertEquals(0, countAnalysis("completed", "none"))
        assertFalse(existsElapsed())
    }

    @Test
    fun `다른 Worker가 가져가 대기로 되돌리지 못했으면 세지 않는다`() {
        givenAnalysisFailure(retryableJob, AnalysisFailureCategory.UNAVAILABLE)
        every { mealAnalysisJobTransactionService.retryMealAnalysis(any()) } returns false

        analysisService.analyzeMealAnalysisJob(retryableJob)

        assertEquals(0, countAnalysis("retried", "unavailable"))
    }

    // 인식 작업

    private val recognitionJob = retryableJob.copy(category = MealAnalysisJobCategory.RECOGNITION)
    private val imageBytes = byteArrayOf(1, 2, 3)
    private val recognition = MealRecognitionResult(
        inputCategory = MealInputCategory.FOOD_PHOTO,
        name = "불고기 백반",
        foods = listOf(MealFoodRecognitionResult(name = "불고기", portion = BigDecimal("1.5"))),
        model = "gemini-recognition-test",
        promptVersion = "v1",
    )

    private fun givenImage(job: MealAnalysisJobResult, contentType: String? = "image/jpeg") {
        every { analysisImageClient.downloadImage(job.imageKey) } returns
            S3ObjectContentResult(bytes = imageBytes, contentType = contentType)
    }

    private fun givenRecognitionSuccess(job: MealAnalysisJobResult) {
        givenImage(job)
        every { mealRecognitionClient.recognizeMeal(imageBytes, "image/jpeg", job.language, null) } returns recognition
        every { mealAnalysisJobTransactionService.confirmMealRecognition(any(), any(), any()) } returns true
    }

    private fun givenRecognitionFailure(job: MealAnalysisJobResult, category: AnalysisFailureCategory) {
        givenImage(job)
        every { mealRecognitionClient.recognizeMeal(imageBytes, "image/jpeg", job.language, null) } throws
            AnalysisFailureException(category, "실패")
    }

    @Test
    fun `인식 작업은 사진을 받아 인식하고 영양 작업을 미룰 시간과 함께 확정한다`() {
        givenRecognitionSuccess(recognitionJob)

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        verify(exactly = 1) {
            mealAnalysisJobTransactionService.confirmMealRecognition(recognitionJob, recognition, nutritionDelay)
        }
        // 인식 작업은 v1 분석을 부르지 않는다.
        verify(exactly = 0) { nutritionAnalysisClient.analyzeNutrition(any(), any()) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.confirmMealAnalysis(any(), any()) }
    }

    @Test
    fun `인식 작업은 식사를 기록한 요청의 언어로 인식한다`() {
        val japaneseJob = recognitionJob.copy(language = Language.JA)
        givenRecognitionSuccess(japaneseJob)

        analysisService.analyzeMealAnalysisJob(japaneseJob)

        verify(exactly = 1) { mealRecognitionClient.recognizeMeal(imageBytes, "image/jpeg", Language.JA, null) }
    }

    @Test
    fun `인식에 성공해도 알림을 보내지 않는다`() {
        givenRecognitionSuccess(recognitionJob)

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    @Test
    fun `인식에 성공하면 recognized로 세고 작업이 쌓인 때부터 걸린 시간을 남긴다`() {
        givenRecognitionSuccess(recognitionJob)

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        assertEquals(1, countAnalysis("recognized", "none"))
        assertEquals(30.0, findElapsed("recognized")?.totalTime(TimeUnit.SECONDS))
    }

    @Test
    fun `다른 Worker가 가져가 인식 결과를 쓰지 못했으면 아무것도 세지 않는다`() {
        givenRecognitionSuccess(recognitionJob)
        every { mealAnalysisJobTransactionService.confirmMealRecognition(any(), any(), any()) } returns false

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        assertEquals(0, countAnalysis("recognized", "none"))
        assertFalse(existsElapsed())
    }

    @Test
    fun `인식 작업이 음식이 아니라고 하면 FAILED로 끝내고 음식 사진이 아니라고 알린다`() {
        givenRecognitionFailure(recognitionJob, AnalysisFailureCategory.NOT_FOOD)

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(recognitionJob) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.confirmMealRecognition(any(), any(), any()) }
        assertFailedPushSent(title = "음식 사진이 아닌 것 같다냥 🐾", body = "음식이 보이게 다시 찍어줄래?")
        assertEquals(1, countAnalysis("failed", "not_food"))
    }

    @Test
    fun `인식이 일시적으로 실패하고 다시 시도할 수 있으면 대기로 되돌리고 알리지 않는다`() {
        givenRecognitionFailure(recognitionJob, AnalysisFailureCategory.UNAVAILABLE)

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.retryMealAnalysis(recognitionJob) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.failMealAnalysis(any()) }
        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    @Test
    fun `인식이 재시도까지 실패하면 FAILED로 끝내고 잠시 뒤에 다시 하라고 알린다`() {
        val lastRecognitionJob = lastJob.copy(category = MealAnalysisJobCategory.RECOGNITION)
        givenRecognitionFailure(lastRecognitionJob, AnalysisFailureCategory.UNAVAILABLE)

        analysisService.analyzeMealAnalysisJob(lastRecognitionJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(lastRecognitionJob) }
        assertFailedPushSent(title = "지금은 소화가 안 된다냥 🐾", body = "잠시 뒤에 다시 시도해줄래?")
    }

    @Test
    fun `인식할 사진을 받지 못하면 인식을 부르지 않고 그 실패 분류대로 끝낸다`() {
        every { analysisImageClient.downloadImage(recognitionJob.imageKey) } throws
            AnalysisFailureException(AnalysisFailureCategory.INVALID_REQUEST, "분석할 이미지가 없습니다")

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        verify(exactly = 0) { mealRecognitionClient.recognizeMeal(any(), any(), any(), any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(recognitionJob) }
        assertEquals(1, countAnalysis("failed", "invalid_request"))
    }

    @Test
    fun `인식할 사진의 콘텐츠 타입이 없으면 인식을 부르지 않고 FAILED로 끝낸다`() {
        givenImage(recognitionJob, contentType = null)

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        verify(exactly = 0) { mealRecognitionClient.recognizeMeal(any(), any(), any(), any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(recognitionJob) }
        assertEquals(1, countAnalysis("failed", "invalid_request"))
    }

    @Test
    fun `종류가 없는 작업은 인식하지 않고 v1 분석을 한다`() {
        givenAnalysisSuccess(retryableJob)

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 1) { nutritionAnalysisClient.analyzeNutrition(retryableJob.imageKey, retryableJob.language) }
        verify(exactly = 0) { analysisImageClient.downloadImage(any()) }
        verify(exactly = 0) { mealRecognitionClient.recognizeMeal(any(), any(), any(), any()) }
    }

    // 영양 작업

    private val nutritionJob = retryableJob.copy(category = MealAnalysisJobCategory.NUTRITION)
    private val mealAt = now.minusSeconds(600)
    private val bulgogiServingNutrition = createServingNutrition("350", "15.5", "25.25", "20")
    private val riceServingNutrition = createServingNutrition("300", "65", "6", "1")

    private fun createServingNutrition(calory: String, carbs: String, protein: String, fat: String) = ServingNutrition(
        calory = BigDecimal(calory),
        carbs = BigDecimal(carbs),
        protein = BigDecimal(protein),
        fat = BigDecimal(fat),
    )

    /** 불고기 1.5인분(id 101)과 잡곡밥 1인분(id 102). 1인분과 아이콘은 인자로 준 값만 이미 저장돼 있다. */
    private fun createAnalysisMeal(
        bulgogiServingNutrition: ServingNutrition? = null,
        bulgogiIconId: Long? = null,
        riceServingNutrition: ServingNutrition? = null,
        riceIconId: Long? = null,
        hasCatComment: Boolean = false,
        inputCategory: MealInputCategory = MealInputCategory.FOOD_PHOTO,
    ) = AnalysisMealResult(
        userId = userId,
        mealAt = mealAt,
        inputCategory = inputCategory,
        hasCatComment = hasCatComment,
        mealFoods = listOf(
            AnalysisMealFoodResult(101L, "불고기", BigDecimal("1.5"), bulgogiServingNutrition, bulgogiIconId),
            AnalysisMealFoodResult(102L, "잡곡밥", BigDecimal("1.0"), riceServingNutrition, riceIconId),
        ),
    )

    private fun givenAnalysisMeal(meal: AnalysisMealResult?) {
        every { mealAnalysisJobTransactionService.getAnalysisMeal(any()) } returns meal
        every { mealAnalysisJobTransactionService.confirmMealNutrition(any(), any(), any()) } returns
            createPushResult(MealStatus.COMPLETED)
        every { catService.createCatComment(any(), any(), any(), any(), any()) } returns "든든하게 먹었다냥"
    }

    private fun givenServingNutritions(
        names: List<String>,
        results: List<ServingNutritionResult>,
        inputCategory: MealInputCategory = MealInputCategory.FOOD_PHOTO,
    ) {
        every { servingNutritionClient.getServingNutritions(names, inputCategory) } returns results
    }

    @Test
    fun `영양 작업은 1인분이 비어 있는 줄을 모두 조회해 줄마다 결과를 짝지어 확정한다`() {
        givenAnalysisMeal(createAnalysisMeal())
        givenServingNutritions(
            listOf("불고기", "잡곡밥"),
            listOf(
                ServingNutritionResult(bulgogiServingNutrition, 12L),
                ServingNutritionResult(riceServingNutrition, 5L),
            ),
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            mealAnalysisJobTransactionService.confirmMealNutrition(
                nutritionJob,
                mapOf(
                    101L to ServingNutritionResult(bulgogiServingNutrition, 12L),
                    102L to ServingNutritionResult(riceServingNutrition, 5L),
                ),
                "든든하게 먹었다냥",
            )
        }
        // 영양 작업은 사진을 다시 보지 않는다.
        verify(exactly = 0) { nutritionAnalysisClient.analyzeNutrition(any(), any()) }
        verify(exactly = 0) { analysisImageClient.downloadImage(any()) }
    }

    @Test
    fun `1인분과 아이콘이 모두 있는 줄은 조회하지 않는다`() {
        givenAnalysisMeal(createAnalysisMeal(riceServingNutrition = riceServingNutrition, riceIconId = 5L))
        givenServingNutritions(listOf("불고기"), listOf(ServingNutritionResult(bulgogiServingNutrition, 12L)))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) { servingNutritionClient.getServingNutritions(listOf("불고기"), any()) }
        verify(exactly = 1) {
            mealAnalysisJobTransactionService.confirmMealNutrition(
                nutritionJob,
                mapOf(101L to ServingNutritionResult(bulgogiServingNutrition, 12L)),
                any(),
            )
        }
    }

    @Test
    fun `1인분은 있고 아이콘만 비어 있는 줄도 조회한다`() {
        givenAnalysisMeal(
            createAnalysisMeal(
                bulgogiServingNutrition = bulgogiServingNutrition,
                riceServingNutrition = riceServingNutrition,
                riceIconId = 5L,
            ),
        )
        givenServingNutritions(listOf("불고기"), listOf(ServingNutritionResult(riceServingNutrition, 12L)))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) { servingNutritionClient.getServingNutritions(listOf("불고기"), any()) }
    }

    @Test
    fun `조회할 줄이 없으면 모델을 부르지 않고 빈 결과로 확정한다`() {
        givenAnalysisMeal(
            createAnalysisMeal(
                bulgogiServingNutrition = bulgogiServingNutrition,
                bulgogiIconId = 12L,
                riceServingNutrition = riceServingNutrition,
                riceIconId = 5L,
            ),
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 0) { servingNutritionClient.getServingNutritions(any(), any()) }
        verify(exactly = 1) {
            mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, emptyMap(), any())
        }
    }

    @Test
    fun `1인분은 식사의 입력 유형에 맞게 조회한다`() {
        givenAnalysisMeal(createAnalysisMeal(inputCategory = MealInputCategory.PACKAGE))
        givenServingNutritions(
            listOf("불고기", "잡곡밥"),
            listOf(
                ServingNutritionResult(bulgogiServingNutrition, 12L),
                ServingNutritionResult(riceServingNutrition, 5L),
            ),
            inputCategory = MealInputCategory.PACKAGE,
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            servingNutritionClient.getServingNutritions(listOf("불고기", "잡곡밥"), MealInputCategory.PACKAGE)
        }
    }

    @Test
    fun `코멘트는 저장된 1인분과 방금 조회한 1인분으로 구한 줄 영양과 합계로, 작업의 언어로 만든다`() {
        // 잡곡밥은 사용자가 고쳐 둔 1인분이 있고, 불고기만 방금 조회한다.
        val japaneseJob = nutritionJob.copy(language = Language.JA)
        givenAnalysisMeal(createAnalysisMeal(riceServingNutrition = riceServingNutrition, riceIconId = 5L))
        givenServingNutritions(listOf("불고기"), listOf(ServingNutritionResult(bulgogiServingNutrition, 12L)))

        analysisService.analyzeMealAnalysisJob(japaneseJob)

        verify(exactly = 1) {
            catService.createCatComment(
                userId = userId,
                mealAt = mealAt,
                mealFoods = listOf(
                    // 350 × 1.5 = 525, 15.5 × 1.5 = 23.25 → 23, 25.25 × 1.5 = 37.875 → 38, 20 × 1.5 = 30
                    CatCommentMealFoodInput("불고기", BigDecimal("1.5"), Nutrition(525, 23, 38, 30)),
                    CatCommentMealFoodInput("잡곡밥", BigDecimal("1.0"), Nutrition(300, 65, 6, 1)),
                ),
                // 합계는 소수로 더한 뒤 한 번 반올림한다: 탄수화물 23.25 + 65 = 88.25 → 88, 단백질 37.875 + 6 = 43.875 → 44
                nutrition = Nutrition(calory = 825, carbs = 88, protein = 44, fat = 31),
                language = Language.JA,
            )
        }
    }

    @Test
    fun `이미 코멘트가 있는 식사는 코멘트를 다시 만들지 않는다`() {
        givenAnalysisMeal(createAnalysisMeal(hasCatComment = true))
        givenServingNutritions(
            listOf("불고기", "잡곡밥"),
            listOf(
                ServingNutritionResult(bulgogiServingNutrition, 12L),
                ServingNutritionResult(riceServingNutrition, 5L),
            ),
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 0) { catService.createCatComment(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, any(), null) }
    }

    @Test
    fun `영양 작업이 끝나면 completed로 세고 작업의 언어로 완료 알림을 보낸다`() {
        val japaneseJob = nutritionJob.copy(language = Language.JA)
        givenAnalysisMeal(createAnalysisMeal(bulgogiServingNutrition, 12L, riceServingNutrition, 5L))

        analysisService.analyzeMealAnalysisJob(japaneseJob)

        assertEquals(PushType.ANALYSIS_COMPLETED, sentMessage.captured.type)
        assertEquals(
            messageSource.getMessage("push.analysis.completed.title", Language.JA, "나비"),
            sentMessage.captured.title,
        )
        assertEquals(1, countAnalysis("completed", "none"))
    }

    @Test
    fun `음식 목록을 다시 보내 작업이 바뀌었으면 결과를 쓰지 못하고 알리지도 세지도 않는다`() {
        givenAnalysisMeal(createAnalysisMeal(bulgogiServingNutrition, 12L, riceServingNutrition, 5L))
        every { mealAnalysisJobTransactionService.confirmMealNutrition(any(), any(), any()) } returns null

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
        assertEquals(0, countAnalysis("completed", "none"))
    }

    @Test
    fun `1인분 조회가 일시적으로 실패하고 다시 시도할 수 있으면 코멘트를 만들지 않고 대기로 되돌린다`() {
        givenAnalysisMeal(createAnalysisMeal())
        every { servingNutritionClient.getServingNutritions(any(), any()) } throws
            AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "실패")

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.retryMealAnalysis(nutritionJob) }
        verify(exactly = 0) { catService.createCatComment(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.confirmMealNutrition(any(), any(), any()) }
        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    @Test
    fun `1인분 조회 결과를 해석하지 못하면 FAILED로 끝내고 알린다`() {
        givenAnalysisMeal(createAnalysisMeal())
        every { servingNutritionClient.getServingNutritions(any(), any()) } throws
            AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "실패")

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(nutritionJob) }
        assertFailedPushSent(title = "사진을 못 알아봤다냥 🐾", body = "다시 시도해줄래?")
    }

    @Test
    fun `음식 줄이 없는 식사의 영양 작업은 모델을 부르지 않고 FAILED로 끝낸다`() {
        givenAnalysisMeal(createAnalysisMeal().copy(mealFoods = emptyList()))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 0) { servingNutritionClient.getServingNutritions(any(), any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(nutritionJob) }
        assertEquals(1, countAnalysis("failed", "invalid_request"))
    }

    @Test
    fun `영양 작업의 식사 행이 없으면 아무것도 하지 않는다`() {
        givenAnalysisMeal(null)

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 0) { servingNutritionClient.getServingNutritions(any(), any()) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.confirmMealNutrition(any(), any(), any()) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.failMealAnalysis(any()) }
    }

    // 지표의 작업 종류 태그

    private fun countAnalysisByJob(job: String, outcome: String): Long =
        meterRegistry.find("nyummy.meal.analysis").tags("job", job, "outcome", outcome).timer()?.count() ?: 0

    private fun findElapsedByJob(job: String, outcome: String) =
        meterRegistry.find("nyummy.meal.analysis.elapsed").tags("job", job, "outcome", outcome).timer()

    @Test
    fun `종류가 없는 작업은 지표에 v1로 남긴다`() {
        givenAnalysisSuccess(retryableJob)

        analysisService.analyzeMealAnalysisJob(retryableJob)

        assertEquals(1, countAnalysisByJob("v1", "completed"))
        assertEquals(1, findElapsedByJob("v1", "completed")?.count())
    }

    @Test
    fun `인식 작업은 지표에 recognition으로 남긴다`() {
        givenRecognitionSuccess(recognitionJob)

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        assertEquals(1, countAnalysisByJob("recognition", "recognized"))
        assertEquals(1, findElapsedByJob("recognition", "recognized")?.count())
    }

    @Test
    fun `영양 작업은 지표에 nutrition으로 남긴다`() {
        givenAnalysisMeal(createAnalysisMeal(bulgogiServingNutrition, 12L, riceServingNutrition, 5L))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(1, countAnalysisByJob("nutrition", "completed"))
        assertEquals(1, findElapsedByJob("nutrition", "completed")?.count())
    }

    @Test
    fun `실패와 재시도도 작업 종류를 나눠 남긴다`() {
        givenRecognitionFailure(recognitionJob, AnalysisFailureCategory.UNAVAILABLE)

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        assertEquals(1, countAnalysisByJob("recognition", "retried"))
        assertEquals(0, countAnalysisByJob("v1", "retried"))
    }

    @Test
    fun `끝날 때까지 걸린 시간은 작업을 가져갈 수 있게 된 때부터 잰다`() {
        // 5분 미뤘다가 10초 전에 가져갈 수 있게 된 영양 작업. 미룬 5분은 걸린 시간에 넣지 않는다.
        val delayedJob = nutritionJob.copy(availableAt = now.minusSeconds(10))
        givenAnalysisMeal(createAnalysisMeal(bulgogiServingNutrition, 12L, riceServingNutrition, 5L))

        analysisService.analyzeMealAnalysisJob(delayedJob)

        assertEquals(10.0, findElapsedByJob("nutrition", "completed")?.totalTime(TimeUnit.SECONDS))
    }
}
