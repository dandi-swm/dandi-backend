package com.dandi.nyummy.worker.service

import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.ai.report.ReportGenerationClient
import com.dandi.nyummy.infra.ai.report.ReportGenerationFailureCategory
import com.dandi.nyummy.infra.ai.report.ReportGenerationFailureException
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushResult
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.report.calculator.NUTRITION_BALANCE_VIEW_TYPE
import com.dandi.nyummy.report.dto.ReportTextResult
import com.dandi.nyummy.report.enum.ReportStatus
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.worker.config.WorkerJobProperties
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.ReportGenerationJobResult
import com.dandi.nyummy.worker.dto.ReportPushResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportGenerationServiceTest {

    private val now = Instant.parse("2026-11-10T03:00:00Z")
    private val userId = 1L
    private val sunday = LocalDate.parse("2026-11-01")
    private val retryDelay = Duration.ofSeconds(30)

    private val reportGenerationClient = mockk<ReportGenerationClient>()
    private val transactionService = mockk<ReportGenerationJobTransactionService>()
    private val mealRepository = mockk<MealRepository>()
    private val profileRepository = mockk<ProfileRepository>()
    private val notificationService = mockk<NotificationService>()
    private val objectMapper = jacksonObjectMapper()

    private val service = ReportGenerationService(
        reportGenerationClient = reportGenerationClient,
        reportGenerationJobTransactionService = transactionService,
        mealRepository = mealRepository,
        profileRepository = profileRepository,
        notificationService = notificationService,
        appLinkProperties = AppLinkProperties(
            "https://link.test",
            "/home",
            "/signup",
            "/meals",
            "/onboarding",
            "/inquiries",
        ),
        workerProperties = WorkerProperties(enabled = true, report = WorkerJobProperties(retryDelay = retryDelay)),
        objectMapper = objectMapper,
        meterRegistry = SimpleMeterRegistry(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private val pushMessage = slot<PushMessage>()

    init {
        every { mealRepository.findMealsByUserIdAndPeriod(userId, any(), any()) } returns emptyList()
        every { profileRepository.findProfileByUserId(userId) } returns null
        every { notificationService.sendServicePush(userId, capture(pushMessage)) } returns
            PushResult(successCount = 1, invalidTokens = emptyList(), retryableTokens = emptyList())
        every { notificationService.deleteInvalidDeviceTokens(any()) } just runs
    }

    private fun job(canRetry: Boolean = true) = ReportGenerationJobResult(
        jobId = 1L,
        reportId = 10L,
        userId = userId,
        startDate = sunday,
        leaseCount = 1,
        canRetry = canRetry,
        createdAt = now.minusSeconds(60),
    )

    private fun pushResult(status: ReportStatus, refundedCoinQuantity: Int = 0) =
        ReportPushResult(10L, userId, sunday, status, refundedCoinQuantity)

    private fun givenGenerationFails(category: ReportGenerationFailureCategory) {
        every { reportGenerationClient.generateReportText(any()) } throws
            ReportGenerationFailureException(category, "실패")
    }

    @Test
    fun `AI 문장과 서버 집계를 합쳐 영양 균형 뷰로 확정하고 리포트 상세로 알린다`() {
        every { reportGenerationClient.generateReportText(any()) } returns
            ReportTextResult("제목", "조언", listOf("하나", "둘", "셋"), "마무리")
        val content = slot<String>()
        every {
            transactionService.confirmReportGeneration(any(), "제목", NUTRITION_BALANCE_VIEW_TYPE, capture(content))
        } returns pushResult(ReportStatus.COMPLETED)

        service.generateReport(job())

        val savedContent = objectMapper.readTree(content.captured)
        assertEquals("조언", savedContent["advice"].asString())
        assertEquals(7, savedContent["days"].size())
        assertTrue(savedContent.has("focusSentence"))
        assertEquals(PushType.REPORT_COMPLETED, pushMessage.captured.type)
        assertEquals("https://link.test/reports/10", pushMessage.captured.appLink)
        assertEquals("11/1~11/7 주간 영양 리포트가 도착했어요", pushMessage.captured.body)
    }

    @Test
    fun `확정할 때 내 작업이 아니면 알리지 않는다`() {
        every { reportGenerationClient.generateReportText(any()) } returns
            ReportTextResult("제목", "조언", listOf("하나", "둘", "셋"), "마무리")
        every { transactionService.confirmReportGeneration(any(), any(), any(), any()) } returns null

        service.generateReport(job())

        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    @Test
    fun `일시적인 실패는 재시도할 수 있으면 retryDelay 뒤로 미루고 알리지 않는다`() {
        givenGenerationFails(ReportGenerationFailureCategory.UNAVAILABLE)
        every { transactionService.retryReportGeneration(any(), retryDelay) } returns true

        service.generateReport(job())

        verify { transactionService.retryReportGeneration(any(), retryDelay) }
        verify(exactly = 0) { transactionService.failReportGeneration(any()) }
        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    @Test
    fun `규칙을 어긴 응답은 재시도할 수 있으면 바로 다시 시도한다`() {
        givenGenerationFails(ReportGenerationFailureCategory.INVALID_RESPONSE)
        every { transactionService.retryReportGeneration(any(), Duration.ZERO) } returns true

        service.generateReport(job())

        verify { transactionService.retryReportGeneration(any(), Duration.ZERO) }
    }

    @Test
    fun `설정 오류는 시도 횟수를 쓰지 않고 미룬다`() {
        givenGenerationFails(ReportGenerationFailureCategory.MISCONFIGURED)
        every { transactionService.cancelReportGeneration(any(), Duration.ofMinutes(1)) } returns true

        service.generateReport(job(canRetry = false))

        verify { transactionService.cancelReportGeneration(any(), Duration.ofMinutes(1)) }
        verify(exactly = 0) { transactionService.failReportGeneration(any()) }
    }

    @Test
    fun `마지막 시도의 일시적인 실패는 실패로 끝내고 돌려준 코인을 알린다`() {
        givenGenerationFails(ReportGenerationFailureCategory.UNAVAILABLE)
        every { transactionService.failReportGeneration(any()) } returns pushResult(ReportStatus.FAILED, 800)

        service.generateReport(job(canRetry = false))

        verify(exactly = 0) { transactionService.retryReportGeneration(any(), any()) }
        assertEquals(PushType.REPORT_FAILED, pushMessage.captured.type)
        assertEquals("11/1~11/7 리포트를 만들지 못해 800코인을 돌려드렸어요", pushMessage.captured.body)
        assertEquals("https://link.test/reports", pushMessage.captured.appLink)
    }

    @Test
    fun `무료 리포트가 실패하면 다시 무료로 만들 수 있다고 알린다`() {
        givenGenerationFails(ReportGenerationFailureCategory.INVALID_REQUEST)
        every { transactionService.failReportGeneration(any()) } returns pushResult(ReportStatus.FAILED, 0)

        service.generateReport(job())

        assertEquals("11/1~11/7 리포트를 만들지 못했어요. 다시 무료로 만들 수 있어요", pushMessage.captured.body)
    }

    @Test
    fun `분류에 없는 예외는 실패로 끝낸다`() {
        every { reportGenerationClient.generateReportText(any()) } throws IllegalStateException("bug")
        every { transactionService.failReportGeneration(any()) } returns pushResult(ReportStatus.FAILED, 0)

        service.generateReport(job())

        verify { transactionService.failReportGeneration(any()) }
    }
}
