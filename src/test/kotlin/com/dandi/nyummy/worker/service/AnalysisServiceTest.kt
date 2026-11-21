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
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.config.RewardBoxProperties
import com.dandi.nyummy.meal.dto.CatCommentMealFoodInput
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.NutritionAnalysisResult
import com.dandi.nyummy.meal.dto.NutritionHint
import com.dandi.nyummy.meal.dto.PhotoServingNutritionInput
import com.dandi.nyummy.meal.dto.PhotoServingNutritionResult
import com.dandi.nyummy.meal.dto.RewardBoxResult
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.enum.NetContentUnit
import com.dandi.nyummy.meal.enum.PackageNutritionMismatchCategory
import com.dandi.nyummy.meal.service.FoodNutritionService
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
import io.mockk.verifyOrder
import org.springframework.dao.QueryTimeoutException
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    private val foodNutritionService = mockk<FoodNutritionService>()
    private val catService = mockk<CatService>()
    private val mealAnalysisJobTransactionService = mockk<MealAnalysisJobTransactionService>()
    private val mealProperties = mockk<MealProperties> {
        every { recognition.nutritionDelay } returns nutritionDelay
        every { rewardBoxes } returns mapOf(
            RewardBoxCategory.BRONZE to RewardBoxProperties(rate = 40, minCoinQuantity = 100, maxCoinQuantity = 199),
            RewardBoxCategory.SILVER to RewardBoxProperties(rate = 40, minCoinQuantity = 200, maxCoinQuantity = 349),
            RewardBoxCategory.GOLD to RewardBoxProperties(rate = 20, minCoinQuantity = 350, maxCoinQuantity = 500),
        )
        every { rewardMailTimeToLive } returns Duration.ofDays(7)
        every { packageNutrition.tolerance } returns 0.1
    }

    // 0~99에서 85가 나오면 골드이고, 골드의 코인 범위에서 412를 뽑는다.
    private val random = mockk<Random> {
        every { nextInt(100) } returns 85
        every { nextInt(350, 501) } returns 412
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
        foodNutritionService = foodNutritionService,
        catService = catService,
        mealAnalysisJobTransactionService = mealAnalysisJobTransactionService,
        mealProperties = mealProperties,
        catRepository = catRepository,
        notificationService = notificationService,
        appLinkProperties = appLinkProperties,
        meterRegistry = meterRegistry,
        clock = clock,
        messageSource = messageSource,
        random = random,
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
        // 기본은 저장해 둔 음식 영양이 없는 상태다. 이름만으로 구하는 줄은 모두 모델에 묻는다.
        every { foodNutritionService.getFoodNutrition(any()) } returns null
        every { foodNutritionService.createFoodNutrition(any(), any()) } just Runs
        // 저장해 둔 제품 영양도 없는 상태다.
        every { foodNutritionService.getPackageNutrition(any(), any(), any()) } returns null
        every { foodNutritionService.createPackageNutrition(any(), any(), any(), any(), any()) } just Runs
        every { foodNutritionService.updatePackageNutrition(any(), any(), any(), any()) } just Runs
        every {
            foodNutritionService.createPackageNutritionMismatch(any(), any(), any(), any(), any(), any(), any())
        } just Runs
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
        foods = listOf(MealFoodRecognitionResult(name = "불고기")),
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
        every { mealAnalysisJobTransactionService.confirmMealRecognition(any(), any(), any(), any(), any()) } returns
            true
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
            mealAnalysisJobTransactionService.confirmMealRecognition(
                job = recognitionJob,
                recognition = recognition,
                nutritionDelay = nutritionDelay,
                rewardBox = any(),
                rewardMailExpiresAt = any(),
            )
        }
        // 인식 작업은 v1 분석을 부르지 않는다.
        verify(exactly = 0) { nutritionAnalysisClient.analyzeNutrition(any(), any()) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.confirmMealAnalysis(any(), any()) }
    }

    @Test
    fun `인식 작업은 뽑은 보상 상자와 지금부터 센 우편 만료 시각을 확정에 넘긴다`() {
        givenRecognitionSuccess(recognitionJob)

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        verify(exactly = 1) {
            mealAnalysisJobTransactionService.confirmMealRecognition(
                job = any(),
                recognition = any(),
                nutritionDelay = any(),
                rewardBox = RewardBoxResult(RewardBoxCategory.GOLD, 412),
                rewardMailExpiresAt = now.plus(Duration.ofDays(7)),
            )
        }
    }

    @Test
    fun `인식 작업은 식사를 기록한 요청의 언어로 인식한다`() {
        val japaneseJob = recognitionJob.copy(language = Language.JA)
        givenRecognitionSuccess(japaneseJob)

        analysisService.analyzeMealAnalysisJob(japaneseJob)

        verify(exactly = 1) { mealRecognitionClient.recognizeMeal(imageBytes, "image/jpeg", Language.JA, null) }
    }

    @Test
    fun `인식 작업의 결과에 요청한 언어가 아닌 글자가 있어도 그대로 확정한다`() {
        // 식사 기록 요청 안에서 섞인 결과는 이 작업으로 넘어와 다시 인식한 것이라, 여기서는 더 넘기지 않는다.
        val japaneseJob = recognitionJob.copy(language = Language.JA)
        givenRecognitionSuccess(japaneseJob)

        analysisService.analyzeMealAnalysisJob(japaneseJob)

        verify(exactly = 1) {
            mealAnalysisJobTransactionService.confirmMealRecognition(
                job = japaneseJob,
                recognition = recognition,
                nutritionDelay = any(),
                rewardBox = any(),
                rewardMailExpiresAt = any(),
            )
        }
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
        every { mealAnalysisJobTransactionService.confirmMealRecognition(any(), any(), any(), any(), any()) } returns
            false

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        assertEquals(0, countAnalysis("recognized", "none"))
        assertFalse(existsElapsed())
    }

    @Test
    fun `인식 작업이 음식이 아니라고 하면 FAILED로 끝내고 음식 사진이 아니라고 알린다`() {
        givenRecognitionFailure(recognitionJob, AnalysisFailureCategory.NOT_FOOD)

        analysisService.analyzeMealAnalysisJob(recognitionJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(recognitionJob) }
        verify(exactly = 0) {
            mealAnalysisJobTransactionService.confirmMealRecognition(any(), any(), any(), any(), any())
        }
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

    /**
     * 불고기 1.5인분(id 101)과 잡곡밥 1인분(id 102). 1인분과 아이콘은 인자로 준 값만 이미 저장돼 있다.
     *
     * @param isRecognized 인식이 사진에서 찾은 줄인지. 기본은 사용자가 더한 줄이라 이름만으로 구한다
     */
    private fun createAnalysisMeal(
        bulgogiServingNutrition: ServingNutrition? = null,
        bulgogiIconId: Long? = null,
        riceServingNutrition: ServingNutrition? = null,
        riceIconId: Long? = null,
        hasCatComment: Boolean = false,
        inputCategory: MealInputCategory = MealInputCategory.FOOD_PHOTO,
        bulgogiPortion: BigDecimal? = BigDecimal("1.5"),
        isRecognized: Boolean = false,
        bulgogiNutritionHint: NutritionHint? = null,
    ) = AnalysisMealResult(
        userId = userId,
        mealAt = mealAt,
        inputCategory = inputCategory,
        hasCatComment = hasCatComment,
        mealFoods = listOf(
            AnalysisMealFoodResult(
                mealFoodId = 101L,
                name = "불고기",
                portion = bulgogiPortion,
                servingNutrition = bulgogiServingNutrition,
                iconId = bulgogiIconId,
                nutritionHint = bulgogiNutritionHint,
                isRecognized = isRecognized,
            ),
            AnalysisMealFoodResult(
                102L,
                "잡곡밥",
                BigDecimal("1.0"),
                riceServingNutrition,
                riceIconId,
                null,
                isRecognized,
            ),
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
        every { servingNutritionClient.getNamedServingNutritions(names, inputCategory) } returns results
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
        // 이름만으로 구하는 줄은 사진을 받지 않는다.
        verify(exactly = 0) { nutritionAnalysisClient.analyzeNutrition(any(), any()) }
        verify(exactly = 0) { analysisImageClient.downloadImage(any()) }
    }

    // 영양 작업: 사진을 함께 보내 구하는 줄

    private fun givenPhotoServingNutritions(
        foods: List<PhotoServingNutritionInput>,
        results: List<ServingNutritionResult>,
        totalNutritionHint: NutritionHint? = null,
    ) {
        givenImage(nutritionJob)
        every { servingNutritionClient.getPhotoServingNutritions(imageBytes, "image/jpeg", foods) } returns
            PhotoServingNutritionResult(results, totalNutritionHint)
    }

    @Test
    fun `음식 사진 식사에서 인식한 줄은 사진을 함께 보내 구하고 구한 인분을 결과에 싣는다`() {
        givenAnalysisMeal(createAnalysisMeal(isRecognized = true, bulgogiPortion = null))
        givenPhotoServingNutritions(
            listOf(PhotoServingNutritionInput("불고기"), PhotoServingNutritionInput("잡곡밥")),
            listOf(
                ServingNutritionResult(bulgogiServingNutrition, 12L, BigDecimal("2.0")),
                ServingNutritionResult(riceServingNutrition, 5L, BigDecimal("1.0")),
            ),
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            mealAnalysisJobTransactionService.confirmMealNutrition(
                nutritionJob,
                mapOf(
                    101L to ServingNutritionResult(bulgogiServingNutrition, 12L, BigDecimal("2.0")),
                    102L to ServingNutritionResult(riceServingNutrition, 5L, BigDecimal("1.0")),
                ),
                any(),
            )
        }
        verify(exactly = 0) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
    }

    @Test
    fun `코멘트의 줄 영양은 비어 있던 인분에 사진으로 구한 인분을 넣어 계산한다`() {
        givenAnalysisMeal(createAnalysisMeal(isRecognized = true, bulgogiPortion = null))
        givenPhotoServingNutritions(
            listOf(PhotoServingNutritionInput("불고기"), PhotoServingNutritionInput("잡곡밥")),
            listOf(
                ServingNutritionResult(bulgogiServingNutrition, 12L, BigDecimal("2.0")),
                // 잡곡밥은 인분이 이미 1.0이라 조회 결과의 인분(3.0)을 쓰지 않는다.
                ServingNutritionResult(riceServingNutrition, 5L, BigDecimal("3.0")),
            ),
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            catService.createCatComment(
                userId = userId,
                mealAt = mealAt,
                mealFoods = listOf(
                    // 350 × 2.0 = 700, 15.5 × 2 = 31, 25.25 × 2 = 50.5 → 51, 20 × 2 = 40
                    CatCommentMealFoodInput("불고기", BigDecimal("2.0"), Nutrition(700, 31, 51, 40)),
                    CatCommentMealFoodInput("잡곡밥", BigDecimal("1.0"), Nutrition(300, 65, 6, 1)),
                ),
                nutrition = Nutrition(calory = 1000, carbs = 96, protein = 57, fat = 41),
                language = nutritionJob.language,
            )
        }
    }

    @Test
    fun `음식 사진 식사여도 사용자가 더한 줄은 이름만으로 구하고 사진을 받지 않는다`() {
        givenAnalysisMeal(createAnalysisMeal(isRecognized = false))
        givenServingNutritions(
            listOf("불고기", "잡곡밥"),
            listOf(
                ServingNutritionResult(bulgogiServingNutrition, 12L),
                ServingNutritionResult(riceServingNutrition, 5L),
            ),
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 0) { servingNutritionClient.getPhotoServingNutritions(any(), any(), any()) }
        verify(exactly = 0) { analysisImageClient.downloadImage(any()) }
    }

    @Test
    fun `음식 사진이 아닌 유형은 인식한 줄이어도 이름만으로 구한다`() {
        listOf(MealInputCategory.TEXT_RECORD, MealInputCategory.ILLUSTRATION, MealInputCategory.PACKAGE)
            .forEach { inputCategory ->
                givenAnalysisMeal(createAnalysisMeal(isRecognized = true, inputCategory = inputCategory))
                givenServingNutritions(
                    listOf("불고기", "잡곡밥"),
                    listOf(
                        ServingNutritionResult(bulgogiServingNutrition, 12L),
                        ServingNutritionResult(riceServingNutrition, 5L),
                    ),
                    inputCategory,
                )

                analysisService.analyzeMealAnalysisJob(nutritionJob)
            }

        verify(exactly = 0) { servingNutritionClient.getPhotoServingNutritions(any(), any(), any()) }
    }

    @Test
    fun `사진을 함께 보내 구한 값은 음식 이름으로 저장하지 않고 저장해 둔 값을 찾지도 않는다`() {
        // 그 사진 속 음식을 보고 구한 값이라 같은 이름의 다른 음식에 다시 쓰지 않는다.
        givenAnalysisMeal(createAnalysisMeal(isRecognized = true, bulgogiPortion = null))
        givenPhotoServingNutritions(
            listOf(PhotoServingNutritionInput("불고기"), PhotoServingNutritionInput("잡곡밥")),
            listOf(
                ServingNutritionResult(bulgogiServingNutrition, 12L, BigDecimal("2.0")),
                ServingNutritionResult(riceServingNutrition, 5L, BigDecimal("1.0")),
            ),
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 0) { foodNutritionService.createFoodNutrition(any(), any()) }
        verify(exactly = 0) { foodNutritionService.getFoodNutrition(any()) }
    }

    @Test
    fun `사진에 식사 전체의 값이 적혀 있으면 인식한 줄들의 합이 그 값과 같아지게 1인분을 맞춘다`() {
        // 불고기 350 × 1.5 + 잡곡밥 300 × 1.0 = 825kcal → 적힌 660kcal(0.8배)
        givenAnalysisMeal(createAnalysisMeal(isRecognized = true))
        givenPhotoServingNutritions(
            listOf(PhotoServingNutritionInput("불고기"), PhotoServingNutritionInput("잡곡밥")),
            listOf(
                ServingNutritionResult(bulgogiServingNutrition, 12L, BigDecimal("1.0")),
                ServingNutritionResult(riceServingNutrition, 5L, BigDecimal("1.0")),
            ),
            totalNutritionHint = NutritionHint(calory = BigDecimal("660.00")),
        )
        val servingNutritions = slot<Map<Long, ServingNutritionResult>>()
        every {
            mealAnalysisJobTransactionService.confirmMealNutrition(any(), capture(servingNutritions), any())
        } returns createPushResult(MealStatus.COMPLETED)

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        // 칼로리만 적혀 있었으므로 칼로리만 맞춘다. 음식별 비율은 구한 값 그대로다.
        assertEquals(BigDecimal("280.00"), servingNutritions.captured.getValue(101L).servingNutrition.calory)
        assertEquals(BigDecimal("240.00"), servingNutritions.captured.getValue(102L).servingNutrition.calory)
        assertEquals(BigDecimal("15.50"), servingNutritions.captured.getValue(101L).servingNutrition.carbs)
    }

    @Test
    fun `인식한 줄 가운데 일부만 다시 구할 때는 식사 전체의 값에 맞추지 않는다`() {
        // 계산이 끝난 식사에서 불고기의 이름만 고쳐 그 줄만 다시 구한다. 잡곡밥은 값이 다 있다.
        givenAnalysisMeal(
            createAnalysisMeal(isRecognized = true, riceServingNutrition = riceServingNutrition, riceIconId = 5L),
        )
        givenPhotoServingNutritions(
            listOf(PhotoServingNutritionInput("불고기")),
            listOf(ServingNutritionResult(bulgogiServingNutrition, 12L, BigDecimal("1.0"))),
            totalNutritionHint = NutritionHint(calory = BigDecimal("660.00")),
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            mealAnalysisJobTransactionService.confirmMealNutrition(
                nutritionJob,
                mapOf(101L to ServingNutritionResult(bulgogiServingNutrition, 12L, BigDecimal("1.0"))),
                any(),
            )
        }
    }

    @Test
    fun `사진에 일부 값이 적혀 있던 인식한 줄은 그 값을 사진 조회에 함께 넘긴다`() {
        val nutritionHint = NutritionHint(calory = BigDecimal("780.00"))
        givenAnalysisMeal(createAnalysisMeal(isRecognized = true, bulgogiNutritionHint = nutritionHint))
        givenPhotoServingNutritions(
            listOf(PhotoServingNutritionInput("불고기", nutritionHint), PhotoServingNutritionInput("잡곡밥")),
            listOf(
                ServingNutritionResult(bulgogiServingNutrition, 12L, BigDecimal("1.0")),
                ServingNutritionResult(riceServingNutrition, 5L, BigDecimal("1.0")),
            ),
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, any(), any()) }
    }

    @Test
    fun `이름만으로 구하는 줄에 칼로리가 적혀 있었으면 구한 값을 그 칼로리에 맞춘다`() {
        // 글씨 유형: 메뉴판에 280kcal만 적혀 있던 불고기. 구한 350kcal의 0.8배로 맞춘다.
        givenAnalysisMeal(
            createAnalysisMeal(
                inputCategory = MealInputCategory.TEXT_RECORD,
                isRecognized = true,
                bulgogiNutritionHint = NutritionHint(calory = BigDecimal("280.00")),
            ),
        )
        givenServingNutritions(
            listOf("불고기", "잡곡밥"),
            listOf(
                ServingNutritionResult(bulgogiServingNutrition, 12L),
                ServingNutritionResult(riceServingNutrition, 5L),
            ),
            MealInputCategory.TEXT_RECORD,
        )
        val servingNutritions = slot<Map<Long, ServingNutritionResult>>()
        every {
            mealAnalysisJobTransactionService.confirmMealNutrition(any(), capture(servingNutritions), any())
        } returns createPushResult(MealStatus.COMPLETED)

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        // 15.5 × 0.8 = 12.40, 25.25 × 0.8 = 20.20, 20 × 0.8 = 16.00
        assertEquals(
            createServingNutrition("280.00", "12.40", "20.20", "16.00"),
            servingNutritions.captured.getValue(101L).servingNutrition,
        )
        assertEquals(riceServingNutrition, servingNutritions.captured.getValue(102L).servingNutrition)
    }

    // 영양 작업: 이름만으로 구하는 줄과 저장해 둔 음식 영양

    private fun captureConfirmedServingNutritions(): () -> Map<Long, ServingNutritionResult> {
        val servingNutritions = slot<Map<Long, ServingNutritionResult>>()
        every {
            mealAnalysisJobTransactionService.confirmMealNutrition(any(), capture(servingNutritions), any())
        } returns createPushResult(MealStatus.COMPLETED)

        return { servingNutritions.captured }
    }

    @Test
    fun `저장해 둔 음식 영양이 있는 이름은 모델을 부르지 않고 그 값으로 확정한다`() {
        givenAnalysisMeal(createAnalysisMeal())
        every { foodNutritionService.getFoodNutrition("불고기") } returns
            ServingNutritionResult(bulgogiServingNutrition, 12L)
        every { foodNutritionService.getFoodNutrition("잡곡밥") } returns ServingNutritionResult(riceServingNutrition, 5L)

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            mealAnalysisJobTransactionService.confirmMealNutrition(
                nutritionJob,
                mapOf(
                    101L to ServingNutritionResult(bulgogiServingNutrition, 12L),
                    102L to ServingNutritionResult(riceServingNutrition, 5L),
                ),
                any(),
            )
        }
        verify(exactly = 0) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
        verify(exactly = 0) { foodNutritionService.createFoodNutrition(any(), any()) }
    }

    @Test
    fun `저장해 둔 음식 영양이 없는 이름만 모델에 묻고 구한 값을 저장한다`() {
        givenAnalysisMeal(createAnalysisMeal())
        every { foodNutritionService.getFoodNutrition("불고기") } returns
            ServingNutritionResult(bulgogiServingNutrition, 12L)
        givenServingNutritions(listOf("잡곡밥"), listOf(ServingNutritionResult(riceServingNutrition, 5L)))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(
            mapOf(
                101L to ServingNutritionResult(bulgogiServingNutrition, 12L),
                102L to ServingNutritionResult(riceServingNutrition, 5L),
            ),
            getConfirmed(),
        )
        verify(exactly = 1) {
            foodNutritionService.createFoodNutrition("잡곡밥", ServingNutritionResult(riceServingNutrition, 5L))
        }
        verify(exactly = 0) { foodNutritionService.createFoodNutrition("불고기", any()) }
    }

    @Test
    fun `제품 유형은 음식 이름으로 저장해 둔 영양을 찾지도 저장하지도 않는다`() {
        listOf(MealInputCategory.PACKAGE, MealInputCategory.NUTRITION_LABEL).forEach { inputCategory ->
            givenAnalysisMeal(createAnalysisMeal(inputCategory = inputCategory, isRecognized = true))
            givenServingNutritions(
                listOf("불고기", "잡곡밥"),
                listOf(
                    ServingNutritionResult(bulgogiServingNutrition, 12L),
                    ServingNutritionResult(riceServingNutrition, 5L),
                ),
                inputCategory,
            )

            analysisService.analyzeMealAnalysisJob(nutritionJob)
        }

        verify(exactly = 2) { servingNutritionClient.getNamedServingNutritions(listOf("불고기", "잡곡밥"), any()) }
        verify(exactly = 0) { foodNutritionService.getFoodNutrition(any()) }
        verify(exactly = 0) { foodNutritionService.createFoodNutrition(any(), any()) }
    }

    @Test
    fun `같은 이름의 줄이 여럿이면 한 번만 묻고 같은 값을 쓴다`() {
        val meal = createAnalysisMeal().let { meal ->
            meal.copy(mealFoods = meal.mealFoods + meal.mealFoods.first().copy(mealFoodId = 103L))
        }
        givenAnalysisMeal(meal)
        givenServingNutritions(
            listOf("불고기", "잡곡밥"),
            listOf(
                ServingNutritionResult(bulgogiServingNutrition, 12L),
                ServingNutritionResult(riceServingNutrition, 5L),
            ),
        )
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(getConfirmed().getValue(101L), getConfirmed().getValue(103L))
        verify(exactly = 1) { foodNutritionService.getFoodNutrition("불고기") }
        verify(exactly = 1) { foodNutritionService.createFoodNutrition("불고기", any()) }
    }

    @Test
    fun `저장해 둔 음식 영양도 줄에 적혀 있던 칼로리에 맞춘다`() {
        // 글씨 유형: 메뉴판에 280kcal만 적혀 있던 불고기. 저장해 둔 350kcal의 0.8배로 맞춘다.
        givenAnalysisMeal(
            createAnalysisMeal(
                inputCategory = MealInputCategory.TEXT_RECORD,
                isRecognized = true,
                bulgogiNutritionHint = NutritionHint(calory = BigDecimal("280.00")),
                riceServingNutrition = riceServingNutrition,
                riceIconId = 5L,
            ),
        )
        every { foodNutritionService.getFoodNutrition("불고기") } returns
            ServingNutritionResult(bulgogiServingNutrition, 12L)
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(
            createServingNutrition("280.00", "12.40", "20.20", "16.00"),
            getConfirmed().getValue(101L).servingNutrition,
        )
        verify(exactly = 0) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
    }

    @Test
    fun `줄에 칼로리가 적혀 있었어도 저장하는 값은 모델이 구한 그대로다`() {
        givenAnalysisMeal(
            createAnalysisMeal(
                inputCategory = MealInputCategory.TEXT_RECORD,
                isRecognized = true,
                bulgogiNutritionHint = NutritionHint(calory = BigDecimal("280.00")),
                riceServingNutrition = riceServingNutrition,
                riceIconId = 5L,
            ),
        )
        givenServingNutritions(
            listOf("불고기"),
            listOf(ServingNutritionResult(bulgogiServingNutrition, 12L)),
            MealInputCategory.TEXT_RECORD,
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            foodNutritionService.createFoodNutrition("불고기", ServingNutritionResult(bulgogiServingNutrition, 12L))
        }
    }

    @Test
    fun `사진을 함께 보낼 줄이 있는데 사진의 콘텐츠 타입이 없으면 FAILED로 끝낸다`() {
        givenAnalysisMeal(createAnalysisMeal(isRecognized = true))
        givenImage(nutritionJob, contentType = null)
        every { mealAnalysisJobTransactionService.failMealAnalysis(nutritionJob) } returns
            createPushResult(MealStatus.FAILED)

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(nutritionJob) }
        verify(exactly = 0) { servingNutritionClient.getPhotoServingNutritions(any(), any(), any()) }
    }

    @Test
    fun `1인분과 아이콘이 모두 있는 줄은 조회하지 않는다`() {
        givenAnalysisMeal(createAnalysisMeal(riceServingNutrition = riceServingNutrition, riceIconId = 5L))
        givenServingNutritions(listOf("불고기"), listOf(ServingNutritionResult(bulgogiServingNutrition, 12L)))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) { servingNutritionClient.getNamedServingNutritions(listOf("불고기"), any()) }
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

        verify(exactly = 1) { servingNutritionClient.getNamedServingNutritions(listOf("불고기"), any()) }
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

        verify(exactly = 0) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
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
            servingNutritionClient.getNamedServingNutritions(listOf("불고기", "잡곡밥"), MealInputCategory.PACKAGE)
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
    fun `인분이 빈 줄은 1인분으로 보고 코멘트에 넣을 줄 영양과 합계를 구한다`() {
        givenAnalysisMeal(
            createAnalysisMeal(riceServingNutrition = riceServingNutrition, riceIconId = 5L, bulgogiPortion = null),
        )
        givenServingNutritions(listOf("불고기"), listOf(ServingNutritionResult(bulgogiServingNutrition, 12L)))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            catService.createCatComment(
                userId = userId,
                mealAt = mealAt,
                mealFoods = listOf(
                    // 저장할 때 빈 인분에 넣는 값(1.0)과 같아야 코멘트의 숫자가 저장된 결과와 맞는다.
                    // 350 × 1.0 = 350, 15.5 → 16, 25.25 → 25, 20
                    CatCommentMealFoodInput("불고기", MealFood.DEFAULT_PORTION, Nutrition(350, 16, 25, 20)),
                    CatCommentMealFoodInput("잡곡밥", BigDecimal("1.0"), Nutrition(300, 65, 6, 1)),
                ),
                // 탄수화물 15.5 + 65 = 80.5 → 81, 단백질 25.25 + 6 = 31.25 → 31
                nutrition = Nutrition(calory = 650, carbs = 81, protein = 31, fat = 21),
                language = nutritionJob.language,
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
    fun `이미 한 번 완료됐던 식사를 다시 계산한 것은 완료 알림을 보내지 않지만 completed로는 센다`() {
        givenAnalysisMeal(
            createAnalysisMeal(bulgogiServingNutrition, 12L, riceServingNutrition, 5L, hasCatComment = true),
        )

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, emptyMap(), null) }
        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
        assertEquals(1, countAnalysis("completed", "none"))
    }

    @Test
    fun `다시 계산이 실패하면 완료였던 식사가 FAILED가 되므로 실패 알림은 보낸다`() {
        givenAnalysisMeal(createAnalysisMeal(hasCatComment = true))
        every { servingNutritionClient.getNamedServingNutritions(any(), any()) } throws
            AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "실패")

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(nutritionJob) }
        assertFailedPushSent(title = "사진을 못 알아봤다냥 🐾", body = "다시 시도해줄래?")
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
        every { servingNutritionClient.getNamedServingNutritions(any(), any()) } throws
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
        every { servingNutritionClient.getNamedServingNutritions(any(), any()) } throws
            AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "실패")

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(nutritionJob) }
        assertFailedPushSent(title = "사진을 못 알아봤다냥 🐾", body = "다시 시도해줄래?")
    }

    @Test
    fun `음식 줄이 없는 식사의 영양 작업은 모델을 부르지 않고 FAILED로 끝낸다`() {
        givenAnalysisMeal(createAnalysisMeal().copy(mealFoods = emptyList()))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 0) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(nutritionJob) }
        assertEquals(1, countAnalysis("failed", "invalid_request"))
    }

    @Test
    fun `영양 작업의 식사 행이 없으면 아무것도 하지 않는다`() {
        givenAnalysisMeal(null)

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 0) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.confirmMealNutrition(any(), any(), any()) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.failMealAnalysis(any()) }
    }

    // 지표의 작업 종류 태그

    private fun countAnalysisByJob(job: String, outcome: String): Long =
        meterRegistry.find("nyummy.meal.analysis").tags("job.category", job, "outcome", outcome).timer()?.count() ?: 0

    private fun findElapsedByJob(job: String, outcome: String) =
        meterRegistry.find("nyummy.meal.analysis.elapsed").tags("job.category", job, "outcome", outcome).timer()

    @Test
    fun `작업 종류 태그의 이름은 job이 아니다`() {
        // 지표를 받는 쪽이 서비스 이름으로 만드는 job 라벨과 이름이 같으면 이 태그가 덮여, 작업 종류가 모두 서비스 이름으로 보인다.
        givenAnalysisSuccess(retryableJob)

        analysisService.analyzeMealAnalysisJob(retryableJob)

        val tagKeys = meterRegistry.meters
            .filter { it.id.name.startsWith("nyummy.meal.analysis") }
            .flatMap { meter -> meter.id.tags.map { it.key } }
        assertTrue(tagKeys.contains("job.category"))
        assertFalse(tagKeys.contains("job"))
    }

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

    // 영양 작업: 제품 유형의 줄과 저장해 둔 제품 영양

    private val packageName = "셀렉스 프로핏"
    private val netContent = BigDecimal("250.0")

    // 사진의 영양성분표에 적혀 있던 값
    private val labelServingNutrition = createServingNutrition("100.00", "4.00", "20.00", "0.70")
    private val labelNutritionHint =
        NutritionHint(BigDecimal("100.00"), BigDecimal("4.00"), BigDecimal("20.00"), BigDecimal("0.70"))

    // 모델이 이름으로 구한 값. 표시값과 허용 오차(10%, 작은 값은 1) 안이다.
    private val namedResult = ServingNutritionResult(createServingNutrition("95", "5", "20", "0"), 6L)

    /** 제품 사진에서 인식한 줄. 포장에서 제품명을 읽었고 아이콘은 아직 없다. */
    private fun createPackageMealFood(
        mealFoodId: Long = 201L,
        name: String = packageName,
        servingNutrition: ServingNutrition? = null,
        nutritionHint: NutritionHint? = null,
        netContent: BigDecimal? = this.netContent,
        netContentUnit: NetContentUnit? = NetContentUnit.ML,
        isNutritionFixed: Boolean = false,
        hasPackageName: Boolean = true,
    ) = AnalysisMealFoodResult(
        mealFoodId = mealFoodId,
        name = name,
        portion = BigDecimal("1.0"),
        servingNutrition = servingNutrition,
        iconId = null,
        nutritionHint = nutritionHint,
        isRecognized = true,
        netContent = netContent,
        netContentUnit = netContentUnit,
        isNutritionFixed = isNutritionFixed,
        hasPackageName = hasPackageName,
    )

    private fun givenPackageMeal(vararg mealFoods: AnalysisMealFoodResult) = givenAnalysisMeal(
        AnalysisMealResult(
            userId = userId,
            mealAt = mealAt,
            inputCategory = MealInputCategory.PACKAGE,
            hasCatComment = false,
            mealFoods = mealFoods.toList(),
        ),
    )

    private fun givenSavedPackageNutrition(result: ServingNutritionResult?) {
        every { foodNutritionService.getPackageNutrition(packageName, netContent, NetContentUnit.ML) } returns result
    }

    private fun givenNamedPackageNutritions(names: List<String>, results: List<ServingNutritionResult>) =
        givenServingNutritions(names, results, MealInputCategory.PACKAGE)

    private fun verifyNoPackageNutritionChange() {
        verify(exactly = 0) { foodNutritionService.createPackageNutrition(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { foodNutritionService.updatePackageNutrition(any(), any(), any(), any()) }
        verify(exactly = 0) {
            foodNutritionService.createPackageNutritionMismatch(any(), any(), any(), any(), any(), any(), any())
        }
    }

    // 사진에 네 값이 다 적혀 있던 줄

    @Test
    fun `네 값이 다 적힌 제품이 저장돼 있고 값이 같으면 모델을 부르지 않고 저장해 둔 아이콘을 쓴다`() {
        givenPackageMeal(createPackageMealFood(servingNutrition = labelServingNutrition))
        givenSavedPackageNutrition(ServingNutritionResult(createServingNutrition("100", "4", "20", "0.7"), 6L))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(6L, getConfirmed().getValue(201L).iconId)
        verify(exactly = 0) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
        verifyNoPackageNutritionChange()
    }

    @Test
    fun `네 값이 다 적힌 제품이 저장돼 있는데 값이 다르면 옛 값을 기록하고 사진 값으로 바꾼다`() {
        val savedServingNutrition = createServingNutrition("110.00", "5.00", "20.00", "1.00")
        givenPackageMeal(createPackageMealFood(servingNutrition = labelServingNutrition))
        givenSavedPackageNutrition(ServingNutritionResult(savedServingNutrition, 6L))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verifyOrder {
            foodNutritionService.createPackageNutritionMismatch(
                category = PackageNutritionMismatchCategory.SAVED,
                name = packageName,
                netContent = netContent,
                netContentUnit = NetContentUnit.ML,
                nutritionHint = labelNutritionHint,
                servingNutrition = savedServingNutrition,
                mealId = mealId,
            )
            foodNutritionService.updatePackageNutrition(
                packageName,
                netContent,
                NetContentUnit.ML,
                labelServingNutrition,
            )
        }
        assertEquals(6L, getConfirmed().getValue(201L).iconId)
        verify(exactly = 0) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
        verify(exactly = 0) { foodNutritionService.createPackageNutrition(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `네 값이 다 적힌 처음 보는 제품은 이름에 용량을 붙여 모델에 묻고 사진 값과 모델이 고른 아이콘을 저장한다`() {
        givenPackageMeal(createPackageMealFood(servingNutrition = labelServingNutrition))
        givenNamedPackageNutritions(listOf("셀렉스 프로핏 250ml"), listOf(namedResult))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            foodNutritionService.createPackageNutrition(
                packageName,
                netContent,
                NetContentUnit.ML,
                labelServingNutrition,
                6L,
            )
        }
        assertEquals(6L, getConfirmed().getValue(201L).iconId)
        // 모델 값이 허용 오차 안이라 기록하지 않는다.
        verify(exactly = 0) {
            foodNutritionService.createPackageNutritionMismatch(any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { foodNutritionService.updatePackageNutrition(any(), any(), any(), any()) }
    }

    @Test
    fun `모델 값이 사진 값과 허용 오차를 넘게 달라도 사진 값을 저장하고 달랐다는 것을 기록한다`() {
        val differentNamedResult = ServingNutritionResult(createServingNutrition("140", "10", "15", "3"), 6L)
        givenPackageMeal(createPackageMealFood(servingNutrition = labelServingNutrition))
        givenNamedPackageNutritions(listOf("셀렉스 프로핏 250ml"), listOf(differentNamedResult))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            foodNutritionService.createPackageNutrition(
                packageName,
                netContent,
                NetContentUnit.ML,
                labelServingNutrition,
                6L,
            )
        }
        verify(exactly = 1) {
            foodNutritionService.createPackageNutritionMismatch(
                category = PackageNutritionMismatchCategory.NAMED,
                name = packageName,
                netContent = netContent,
                netContentUnit = NetContentUnit.ML,
                nutritionHint = labelNutritionHint,
                servingNutrition = differentNamedResult.servingNutrition,
                mealId = mealId,
            )
        }
    }

    @Test
    fun `모델이 먹는 것이 아니라고 본 이름은 사진에 네 값이 다 적혀 있어도 저장하지 않는다`() {
        val notFoodResult = ServingNutritionResult(createServingNutrition("0", "0", "0", "0"), 1L, isFood = false)
        givenPackageMeal(createPackageMealFood(servingNutrition = labelServingNutrition))
        givenNamedPackageNutritions(listOf("셀렉스 프로핏 250ml"), listOf(notFoodResult))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verifyNoPackageNutritionChange()
    }

    // 사진에 일부만 적혀 있던 줄

    private val partialNutritionHint = NutritionHint(calory = BigDecimal("100.00"), protein = BigDecimal("20.00"))

    @Test
    fun `일부만 적힌 제품이 저장돼 있으면 모델을 부르지 않고 저장해 둔 값으로 빈 항목을 채운다`() {
        givenPackageMeal(createPackageMealFood(nutritionHint = partialNutritionHint))
        givenSavedPackageNutrition(ServingNutritionResult(labelServingNutrition, 6L))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(ServingNutritionResult(labelServingNutrition, 6L), getConfirmed().getValue(201L))
        verify(exactly = 0) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
        verifyNoPackageNutritionChange()
    }

    @Test
    fun `일부만 적힌 값이 저장해 둔 값과 다르면 기록만 하고 저장해 둔 값은 바꾸지 않는다`() {
        val savedServingNutrition = createServingNutrition("120.00", "6.00", "20.00", "1.20")
        givenPackageMeal(createPackageMealFood(nutritionHint = partialNutritionHint))
        givenSavedPackageNutrition(ServingNutritionResult(savedServingNutrition, 6L))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            foodNutritionService.createPackageNutritionMismatch(
                category = PackageNutritionMismatchCategory.SAVED,
                name = packageName,
                netContent = netContent,
                netContentUnit = NetContentUnit.ML,
                nutritionHint = partialNutritionHint,
                servingNutrition = savedServingNutrition,
                mealId = mealId,
            )
        }
        verify(exactly = 0) { foodNutritionService.updatePackageNutrition(any(), any(), any(), any()) }
        verify(exactly = 0) { foodNutritionService.createPackageNutrition(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `일부만 적힌 처음 보는 제품은 모델 값으로 빈 항목을 채우고 저장하지 않는다`() {
        givenPackageMeal(createPackageMealFood(nutritionHint = partialNutritionHint))
        givenNamedPackageNutritions(listOf("셀렉스 프로핏 250ml"), listOf(namedResult))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        // 적힌 100kcal에 맞춰 모델의 95kcal 값을 100/95배 한다. 5 × 1.0526… = 5.26, 0 × … = 0.00
        assertEquals(
            ServingNutritionResult(createServingNutrition("100.00", "5.26", "20.00", "0.00"), 6L),
            getConfirmed().getValue(201L),
        )
        verifyNoPackageNutritionChange()
    }

    // 사진에 값이 없던 줄

    @Test
    fun `값이 적혀 있지 않은 제품이 저장돼 있으면 모델을 부르지 않고 저장해 둔 값을 쓴다`() {
        givenPackageMeal(createPackageMealFood())
        givenSavedPackageNutrition(ServingNutritionResult(labelServingNutrition, 6L))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(ServingNutritionResult(labelServingNutrition, 6L), getConfirmed().getValue(201L))
        verify(exactly = 0) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
        verifyNoPackageNutritionChange()
    }

    @Test
    fun `값이 적혀 있지 않은 처음 보는 제품은 모델 값을 쓰고 저장하지 않는다`() {
        givenPackageMeal(createPackageMealFood())
        givenNamedPackageNutritions(listOf("셀렉스 프로핏 250ml"), listOf(namedResult))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(namedResult, getConfirmed().getValue(201L))
        verifyNoPackageNutritionChange()
    }

    // 사용자가 영양을 직접 적은 줄

    @Test
    fun `사용자가 영양을 직접 적은 줄은 아이콘만 받고 그 값을 저장하지도 비교하지도 않는다`() {
        // 저장해 둔 값이 없으면 모델에서, 있으면 저장해 둔 행에서 아이콘을 받는다. 어느 쪽이든 사용자가 적은 값은 사진 값이 아니다.
        val fixedMealFood = createPackageMealFood(
            servingNutrition = createServingNutrition("50.00", "2.00", "10.00", "0.35"),
            isNutritionFixed = true,
        )
        givenPackageMeal(fixedMealFood)
        givenNamedPackageNutritions(listOf("셀렉스 프로핏 250ml"), listOf(namedResult))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(6L, getConfirmed().getValue(201L).iconId)
        verifyNoPackageNutritionChange()

        givenSavedPackageNutrition(ServingNutritionResult(labelServingNutrition, 7L))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(7L, getConfirmed().getValue(201L).iconId)
        verifyNoPackageNutritionChange()
    }

    // 포장에서 제품명을 읽지 못한 줄

    @Test
    fun `제품명을 읽지 못한 줄은 네 값이 다 적혀 있어도 저장하지 않고 모델 값과 달랐다는 기록도 남기지 않는다`() {
        // 영양성분표 면만 찍은 사진. 이름이 그 면의 문구나 종류라, 그 이름으로 저장하면 다른 제품에도 맞는다.
        val differentNamedResult = ServingNutritionResult(createServingNutrition("140", "10", "15", "3"), 6L)
        givenPackageMeal(
            createPackageMealFood(name = "단백질 음료", servingNutrition = labelServingNutrition, hasPackageName = false),
        )
        givenNamedPackageNutritions(listOf("단백질 음료 250ml"), listOf(differentNamedResult))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        // 아이콘은 모델에서 받는다.
        assertEquals(6L, getConfirmed().getValue(201L).iconId)
        verifyNoPackageNutritionChange()
    }

    @Test
    fun `제품명을 읽지 못한 줄은 저장해 둔 값이 있어도 비교하지도 바꾸지도 않고 아이콘만 받는다`() {
        val savedServingNutrition = createServingNutrition("110.00", "5.00", "20.00", "1.00")
        givenPackageMeal(createPackageMealFood(servingNutrition = labelServingNutrition, hasPackageName = false))
        givenSavedPackageNutrition(ServingNutritionResult(savedServingNutrition, 6L))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(6L, getConfirmed().getValue(201L).iconId)
        verify(exactly = 0) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
        verifyNoPackageNutritionChange()
    }

    @Test
    fun `제품명을 읽지 못한 줄도 값이 비어 있으면 저장해 둔 값을 찾아 쓴다`() {
        givenPackageMeal(createPackageMealFood(hasPackageName = false))
        givenSavedPackageNutrition(ServingNutritionResult(labelServingNutrition, 6L))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(ServingNutritionResult(labelServingNutrition, 6L), getConfirmed().getValue(201L))
        verifyNoPackageNutritionChange()
    }

    // 이름과 용량

    @Test
    fun `이름과 용량이 같은 줄이 여럿이면 한 번만 찾고 한 번만 묻는다`() {
        givenPackageMeal(createPackageMealFood(mealFoodId = 201L), createPackageMealFood(mealFoodId = 202L))
        givenNamedPackageNutritions(listOf("셀렉스 프로핏 250ml"), listOf(namedResult))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(getConfirmed().getValue(201L), getConfirmed().getValue(202L))
        verify(exactly = 1) { foodNutritionService.getPackageNutrition(packageName, netContent, NetContentUnit.ML) }
        verify(exactly = 1) { servingNutritionClient.getNamedServingNutritions(any(), any()) }
    }

    @Test
    fun `이름이 같아도 용량이 다르면 다른 제품으로 따로 찾고, 저장돼 있지 않은 것만 모델에 묻는다`() {
        val smallResult = ServingNutritionResult(labelServingNutrition, 6L)
        val largeResult = ServingNutritionResult(createServingNutrition("200", "8", "40", "1.4"), 6L)
        givenPackageMeal(
            createPackageMealFood(mealFoodId = 201L),
            createPackageMealFood(mealFoodId = 202L, netContent = BigDecimal("500.0")),
        )
        givenSavedPackageNutrition(smallResult)
        givenNamedPackageNutritions(listOf("셀렉스 프로핏 500ml"), listOf(largeResult))
        val getConfirmed = captureConfirmedServingNutritions()

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        assertEquals(mapOf(201L to smallResult, 202L to largeResult), getConfirmed())
        verify(exactly = 1) {
            foodNutritionService.getPackageNutrition(packageName, BigDecimal("500.0"), NetContentUnit.ML)
        }
    }

    @Test
    fun `용량을 읽지 못한 제품은 용량 없이 찾고 모델에도 이름만 보낸다`() {
        givenPackageMeal(
            createPackageMealFood(servingNutrition = labelServingNutrition, netContent = null, netContentUnit = null),
        )
        givenNamedPackageNutritions(listOf(packageName), listOf(namedResult))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) { foodNutritionService.getPackageNutrition(packageName, null, null) }
        verify(exactly = 1) {
            foodNutritionService.createPackageNutrition(packageName, null, null, labelServingNutrition, 6L)
        }
    }

    @Test
    fun `용량이 소수이면 그대로, g이면 g을 붙여 모델에 보낸다`() {
        givenPackageMeal(
            createPackageMealFood(name = "꼬북칩", netContent = BigDecimal("16.5"), netContentUnit = NetContentUnit.G),
        )
        givenNamedPackageNutritions(listOf("꼬북칩 16.5g"), listOf(namedResult))

        analysisService.analyzeMealAnalysisJob(nutritionJob)

        verify(exactly = 1) {
            servingNutritionClient.getNamedServingNutritions(listOf("꼬북칩 16.5g"), MealInputCategory.PACKAGE)
        }
    }
}
