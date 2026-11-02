package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisClient
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushResult
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.NutritionAnalysisResult
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.worker.dto.AnalysisPushResult
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.QueryTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * analyzeMealAnalysisJob이 분석 결과와 실패 분류에 따라 확정·재시도·실패를 고르고, 알맞은 알림을 보내는지 다룬다.
 * 트랜잭션 안의 동작(fencing·상태 변경)은 MealAnalysisJobTransactionServiceTest가 맡는다.
 */
class AnalysisServiceTest {

    private val now = Instant.parse("2026-11-02T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userId = 1L
    private val mealId = 10L

    private val nutritionAnalysisClient = mockk<NutritionAnalysisClient>()
    private val mealAnalysisJobTransactionService = mockk<MealAnalysisJobTransactionService>()
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

    private val analysisService = AnalysisService(
        nutritionAnalysisClient = nutritionAnalysisClient,
        mealAnalysisJobTransactionService = mealAnalysisJobTransactionService,
        catRepository = catRepository,
        notificationService = notificationService,
        appLinkProperties = appLinkProperties,
        clock = clock,
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
    )

    private fun createPushResult(status: MealStatus, isDeleted: Boolean = false) =
        AnalysisPushResult(mealId = mealId, userId = userId, mealName = "김치찌개", status = status, isDeleted = isDeleted)

    private fun givenAnalysisSuccess(job: MealAnalysisJobResult) {
        every { nutritionAnalysisClient.analyzeNutrition(job.imageKey) } returns analysisResult
    }

    private fun givenAnalysisFailure(job: MealAnalysisJobResult, category: AnalysisFailureCategory) {
        every { nutritionAnalysisClient.analyzeNutrition(job.imageKey) } throws
            AnalysisFailureException(category, "실패")
    }

    private fun assertFailedPushSent(title: String, body: String) {
        assertEquals(PushType.ANALYSIS_FAILED, sentMessage.captured.type)
        assertEquals(title, sentMessage.captured.title)
        assertEquals(body, sentMessage.captured.body)
    }

    // 성공

    @Test
    fun `분석에 성공하면 결과를 확정하고 완료 알림을 보낸다`() {
        givenAnalysisSuccess(retryableJob)

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 1) { mealAnalysisJobTransactionService.confirmMealAnalysis(retryableJob, analysisResult) }
        assertEquals(
            PushMessage(
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
    fun `일시적인 실패가 마지막 시도면 실패로 끝내고 실패 알림을 보낸다`() {
        givenAnalysisFailure(lastJob, AnalysisFailureCategory.UNAVAILABLE)

        analysisService.analyzeMealAnalysisJob(lastJob)

        verify(exactly = 0) { mealAnalysisJobTransactionService.retryMealAnalysis(any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(lastJob) }
        assertFailedPushSent(title = "사진을 못 알아봤다냥 🐾", body = "다시 시도해줄래?")
    }

    @Test
    fun `종료 정리처럼 실패 처리를 바로 부르면 같은 규칙으로 마지막 시도는 실패로 끝내고 알린다`() {
        analysisService.handleAnalysisFailure(
            lastJob,
            AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "종료"),
        )

        verify(exactly = 0) { nutritionAnalysisClient.analyzeNutrition(any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(lastJob) }
        assertFailedPushSent(title = "사진을 못 알아봤다냥 🐾", body = "다시 시도해줄래?")
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
    fun `분석 중 분류에 없는 예외가 나면 다시 시도할 수 있어도 실패로 끝낸다`() {
        every { nutritionAnalysisClient.analyzeNutrition(retryableJob.imageKey) } throws IllegalStateException("예상 밖")

        analysisService.analyzeMealAnalysisJob(retryableJob)

        verify(exactly = 0) { mealAnalysisJobTransactionService.retryMealAnalysis(any()) }
        verify(exactly = 1) { mealAnalysisJobTransactionService.failMealAnalysis(retryableJob) }
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
}
