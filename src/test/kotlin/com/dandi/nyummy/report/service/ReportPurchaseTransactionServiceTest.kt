package com.dandi.nyummy.report.service

import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.ReportErrorCode
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.report.config.ReportProperties
import com.dandi.nyummy.report.dto.CreateReportPurchaseRequest
import com.dandi.nyummy.report.entity.Report
import com.dandi.nyummy.report.entity.ReportGenerationJob
import com.dandi.nyummy.report.entity.ReportPurchase
import com.dandi.nyummy.report.enum.ReportPurchaseOutcome
import com.dandi.nyummy.report.enum.ReportStatus
import com.dandi.nyummy.report.repository.ReportGenerationJobRepository
import com.dandi.nyummy.report.repository.ReportPurchaseRepository
import com.dandi.nyummy.report.repository.ReportRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReportPurchaseTransactionServiceTest {

    // 2026-11-10 12:00 KST(화요일). 2026-11-01(일)~11-07(토) 주는 이미 끝났고, 11-08(일)부터는 이번 주다.
    private val now = Instant.parse("2026-11-10T03:00:00Z")
    private val userId = 1L
    private val idempotencyKey = "key-1"
    private val lastSunday = LocalDate.parse("2026-11-01")
    private val reportId = 30L
    private val reportPurchaseId = 300L
    private val listPrice = 800

    private val coinService = mockk<CoinService>()
    private val reportPurchaseAttemptService = mockk<ReportPurchaseAttemptService>()
    private val reportRepository = mockk<ReportRepository>()
    private val reportPurchaseRepository = mockk<ReportPurchaseRepository>()
    private val reportGenerationJobRepository = mockk<ReportGenerationJobRepository>()
    private val mealRepository = mockk<MealRepository>()

    private val service = ReportPurchaseTransactionService(
        clock = Clock.fixed(now, ZoneOffset.UTC),
        reportProperties = ReportProperties(listPrice = listPrice, minMealCount = 3),
        coinService = coinService,
        reportPurchaseAttemptService = reportPurchaseAttemptService,
        reportRepository = reportRepository,
        reportPurchaseRepository = reportPurchaseRepository,
        reportGenerationJobRepository = reportGenerationJobRepository,
        mealRepository = mealRepository,
    )

    // 테스트마다 채우는 DB 상태
    private val coin = Coin(userId).also { it.balance = 1000 }
    private var hasFreeReportPurchase = false
    private var completedMealCount = 3L
    private var hasActiveReportInWeek = false

    init {
        every { coinService.lockCoin(userId) } returns coin
        every { coinService.decreaseBalance(coin, any(), any(), any()) } answers {
            coin.decreaseBalance(secondArg())
            coin.balance
        }
        every { reportPurchaseRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey) } returns null
        every { reportPurchaseRepository.existsFreeReportPurchase(userId, ReportStatus.ACTIVE_STATUSES) } answers {
            hasFreeReportPurchase
        }
        every {
            reportRepository.existsByUserIdAndStartDateAndStatusIn(userId, any(), ReportStatus.ACTIVE_STATUSES)
        } answers
            {
                hasActiveReportInWeek
            }
        every {
            mealRepository.countMealsByUserIdAndStatusAndPeriod(userId, MealStatus.COMPLETED, any(), any())
        } answers
            {
                completedMealCount
            }
        every { reportRepository.save(any()) } answers { firstArg<Report>().withId(reportId) }
        every { reportPurchaseRepository.save(any()) } answers { firstArg<ReportPurchase>().withId(reportPurchaseId) }
        every { reportGenerationJobRepository.save(any()) } answers { firstArg() }
        every {
            reportPurchaseAttemptService.createReportPurchaseAttempt(any(), any(), any(), any(), any(), any())
        } just runs
    }

    private fun <T : Any> T.withId(id: Long): T = also { ReflectionTestUtils.setField(it, "id", id) }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    private fun purchase(startDate: LocalDate = lastSunday, purchasePrice: Int = 0) = service.createReportPurchase(
        userId = userId,
        idempotencyKey = idempotencyKey,
        request = CreateReportPurchaseRequest(startDate = startDate, purchasePrice = purchasePrice),
        appVersion = "1.0.0",
    )

    private fun assertNothingSaved() {
        verify(exactly = 0) { reportRepository.save(any()) }
        verify(exactly = 0) { reportPurchaseRepository.save(any()) }
        verify(exactly = 0) { reportGenerationJobRepository.save(any()) }
        verify(exactly = 0) { coinService.decreaseBalance(any(), any(), any(), any()) }
    }

    @Test
    fun `첫 리포트는 무료로 사고 코인 내역을 남기지 않는다`() {
        val response = purchase(purchasePrice = 0)

        assertEquals(reportId, response.reportId)
        assertEquals(ReportStatus.WAITING, response.status)
        assertEquals(0, response.purchasePrice)
        assertEquals(1000, response.balance)
        verify(exactly = 0) { coinService.decreaseBalance(any(), any(), any(), any()) }
    }

    @Test
    fun `무료로 산 리포트가 남아 있으면 정가를 차감하고 구매 id로 코인 내역을 남긴다`() {
        hasFreeReportPurchase = true

        val response = purchase(purchasePrice = listPrice)

        assertEquals(listPrice, response.purchasePrice)
        assertEquals(200, response.balance)
        verify { coinService.decreaseBalance(coin, listPrice, CoinHistoryCategory.REPORT_PURCHASE, reportPurchaseId) }
    }

    @Test
    fun `리포트와 구매와 생성 작업을 함께 만들고 성공 시도를 남긴다`() {
        val savedReport = slot<Report>()
        val savedPurchase = slot<ReportPurchase>()
        val savedJob = slot<ReportGenerationJob>()
        every { reportRepository.save(capture(savedReport)) } answers { firstArg<Report>().withId(reportId) }
        every { reportPurchaseRepository.save(capture(savedPurchase)) } answers {
            firstArg<ReportPurchase>().withId(reportPurchaseId)
        }
        every { reportGenerationJobRepository.save(capture(savedJob)) } answers { firstArg() }

        purchase()

        assertEquals(lastSunday, savedReport.captured.startDate)
        assertEquals(ReportStatus.WAITING, savedReport.captured.status)
        assertEquals(reportId, savedPurchase.captured.reportId)
        assertEquals(0, savedPurchase.captured.purchasePrice)
        assertEquals(idempotencyKey, savedPurchase.captured.idempotencyKey)
        assertEquals(reportId, savedJob.captured.reportId)
        verify {
            reportPurchaseAttemptService.createReportPurchaseAttempt(
                userId,
                idempotencyKey,
                any(),
                "1.0.0",
                ReportPurchaseOutcome.SUCCEEDED,
                reportPurchaseId,
            )
        }
    }

    @Test
    fun `코인 행을 가장 먼저 잠근다`() {
        purchase()

        verifyOrder {
            coinService.lockCoin(userId)
            reportPurchaseRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
            reportRepository.existsByUserIdAndStartDateAndStatusIn(userId, lastSunday, ReportStatus.ACTIVE_STATUSES)
        }
    }

    @Test
    fun `그 주를 Asia_Seoul 기준 일요일 0시부터 다음 일요일 0시 전까지 센다`() {
        purchase()

        verify {
            mealRepository.countMealsByUserIdAndStatusAndPeriod(
                userId,
                MealStatus.COMPLETED,
                Instant.parse("2026-10-31T15:00:00Z"),
                Instant.parse("2026-11-07T15:00:00Z"),
            )
        }
    }

    // 같은 Idempotency-Key

    @Test
    fun `같은 키로 같은 주와 가격을 다시 보내면 새로 만들지 않고 처음 결과를 돌려준다`() {
        val reportPurchase = ReportPurchase(userId, reportId, listPrice, idempotencyKey).withId(reportPurchaseId)
        every { reportPurchaseRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey) } returns reportPurchase
        every { reportRepository.findById(reportId) } returns Optional.of(Report(userId, lastSunday).withId(reportId))

        val response = purchase(purchasePrice = listPrice)

        assertEquals(reportId, response.reportId)
        assertEquals(ReportStatus.WAITING, response.status)
        assertEquals(listPrice, response.purchasePrice)
        assertEquals(1000, response.balance)
        assertNothingSaved()
        verify {
            reportPurchaseAttemptService.createReportPurchaseAttempt(
                userId,
                idempotencyKey,
                any(),
                any(),
                ReportPurchaseOutcome.REPLAYED,
                reportPurchaseId,
            )
        }
    }

    @Test
    fun `같은 키로 다른 주나 가격을 보내면 거부한다`() {
        val reportPurchase = ReportPurchase(userId, reportId, listPrice, idempotencyKey).withId(reportPurchaseId)
        every { reportPurchaseRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey) } returns reportPurchase
        every { reportRepository.findById(reportId) } returns Optional.of(Report(userId, lastSunday).withId(reportId))

        assertBusinessException(ReportErrorCode.IDEMPOTENCY_KEY_REUSED) {
            purchase(startDate = lastSunday.minusWeeks(1), purchasePrice = listPrice)
        }
        assertBusinessException(ReportErrorCode.IDEMPOTENCY_KEY_REUSED) { purchase(purchasePrice = 0) }
        assertNothingSaved()
    }

    // 거부

    @Test
    fun `일요일이 아니거나 아직 끝나지 않은 주면 거부한다`() {
        assertBusinessException(ReportErrorCode.INVALID_WEEK) { purchase(startDate = lastSunday.plusDays(1)) }
        assertBusinessException(ReportErrorCode.INVALID_WEEK) { purchase(startDate = lastSunday.plusWeeks(1)) }
        assertNothingSaved()
    }

    @Test
    fun `취소·실패하지 않은 같은 주의 리포트가 있으면 거부한다`() {
        hasActiveReportInWeek = true

        assertBusinessException(ReportErrorCode.DUPLICATE_WEEK) { purchase() }
        assertNothingSaved()
    }

    @Test
    fun `분석이 완료된 식사가 최소 개수보다 적으면 거부하고 같으면 산다`() {
        completedMealCount = 2

        assertBusinessException(ReportErrorCode.NOT_ENOUGH_MEALS) { purchase() }
        assertNothingSaved()

        completedMealCount = 3
        assertEquals(reportId, purchase().reportId)
    }

    @Test
    fun `화면에서 본 가격이 지금 가격과 다르면 거부한다`() {
        hasFreeReportPurchase = true

        assertBusinessException(ReportErrorCode.PRICE_CHANGED) { purchase(purchasePrice = 0) }
        assertNothingSaved()
    }

    @Test
    fun `가격이 잔액보다 많으면 거부하고 잔액과 같으면 산다`() {
        hasFreeReportPurchase = true
        coin.balance = listPrice - 1

        assertBusinessException(ReportErrorCode.INSUFFICIENT_BALANCE) { purchase(purchasePrice = listPrice) }
        assertNothingSaved()

        coin.balance = listPrice
        assertEquals(0, purchase(purchasePrice = listPrice).balance)
    }
}
