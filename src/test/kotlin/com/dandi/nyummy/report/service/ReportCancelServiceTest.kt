package com.dandi.nyummy.report.service

import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.ReportErrorCode
import com.dandi.nyummy.report.entity.Report
import com.dandi.nyummy.report.entity.ReportPurchase
import com.dandi.nyummy.report.entity.ReportPurchaseRefund
import com.dandi.nyummy.report.enum.ReportPurchaseRefundCategory
import com.dandi.nyummy.report.enum.ReportStatus
import com.dandi.nyummy.report.repository.ReportGenerationJobRepository
import com.dandi.nyummy.report.repository.ReportPurchaseRefundRepository
import com.dandi.nyummy.report.repository.ReportPurchaseRepository
import com.dandi.nyummy.report.repository.ReportRepository
import io.mockk.every
import io.mockk.mockk
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

class ReportCancelServiceTest {

    private val now = Instant.parse("2026-11-10T03:00:00Z")
    private val userId = 1L
    private val reportId = 10L

    private val coinService = mockk<CoinService>()
    private val reportPurchaseRefundService = mockk<ReportPurchaseRefundService>()
    private val reportRepository = mockk<ReportRepository>()
    private val reportGenerationJobRepository = mockk<ReportGenerationJobRepository>()
    private val reportPurchaseRepository = mockk<ReportPurchaseRepository>()
    private val reportPurchaseRefundRepository = mockk<ReportPurchaseRefundRepository>()

    private val service = ReportCancelService(
        clock = Clock.fixed(now, ZoneOffset.UTC),
        coinService = coinService,
        reportPurchaseRefundService = reportPurchaseRefundService,
        reportRepository = reportRepository,
        reportGenerationJobRepository = reportGenerationJobRepository,
        reportPurchaseRepository = reportPurchaseRepository,
        reportPurchaseRefundRepository = reportPurchaseRefundRepository,
    )

    private val coin = Coin(userId).also { it.balance = 200 }

    init {
        every { coinService.lockCoin(userId) } returns coin
        every { reportGenerationJobRepository.findReportGenerationJobIdByReportId(reportId) } returns 1L
        every { reportGenerationJobRepository.deleteReportGenerationJobById(1L) } returns 1
        every { reportRepository.updateReportStatuses(any(), any(), any(), any()) } returns 1
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    private fun givenReport(status: ReportStatus, ownerId: Long = userId) {
        every { reportRepository.findById(reportId) } returns Optional.of(
            Report(ownerId, LocalDate.parse("2026-11-01")).also {
                ReflectionTestUtils.setField(it, "id", reportId)
                it.status = status
            },
        )
    }

    private fun givenRefund(quantity: Int) {
        every {
            reportPurchaseRefundService.createReportPurchaseRefund(
                userId,
                reportId,
                ReportPurchaseRefundCategory.CANCELED,
            )
        } answers {
            coin.balance += quantity
            quantity
        }
    }

    private fun assertNothingChanged() {
        verify(exactly = 0) { reportGenerationJobRepository.deleteReportGenerationJobById(any()) }
        verify(exactly = 0) { reportRepository.updateReportStatuses(any(), any(), any(), any()) }
        verify(exactly = 0) { reportPurchaseRefundService.createReportPurchaseRefund(any(), any(), any()) }
    }

    @Test
    fun `생성 중인 유료 리포트를 취소하면 코인 → 작업 → 리포트 순서로 잠그고 환불한다`() {
        givenReport(ReportStatus.GENERATING)
        givenRefund(800)

        val response = service.cancelReport(userId, reportId)

        assertEquals(ReportStatus.CANCELED, response.status)
        assertEquals(800, response.refundedCoinQuantity)
        assertEquals(1000, response.balance)
        verifyOrder {
            coinService.lockCoin(userId)
            reportGenerationJobRepository.deleteReportGenerationJobById(1L)
            reportRepository.updateReportStatuses(
                listOf(reportId),
                ReportStatus.IN_PROGRESS_STATUSES,
                ReportStatus.CANCELED,
                now,
            )
            reportPurchaseRefundService.createReportPurchaseRefund(
                userId,
                reportId,
                ReportPurchaseRefundCategory.CANCELED,
            )
        }
    }

    @Test
    fun `무료 리포트를 취소하면 돌려준 코인은 0이다`() {
        givenReport(ReportStatus.WAITING)
        givenRefund(0)

        val response = service.cancelReport(userId, reportId)

        assertEquals(0, response.refundedCoinQuantity)
        assertEquals(200, response.balance)
    }

    @Test
    fun `이미 취소한 리포트를 다시 취소하면 처음 환불 기록으로 같은 응답을 주고 아무것도 바꾸지 않는다`() {
        givenReport(ReportStatus.CANCELED)
        every { reportPurchaseRepository.findByReportId(reportId) } returns
            ReportPurchase(userId, reportId, 800, "key").also { ReflectionTestUtils.setField(it, "id", 100L) }
        every { reportPurchaseRefundRepository.findByReportPurchaseId(100L) } returns
            ReportPurchaseRefund(100L, ReportPurchaseRefundCategory.CANCELED, 800)

        val response = service.cancelReport(userId, reportId)

        assertEquals(ReportStatus.CANCELED, response.status)
        assertEquals(800, response.refundedCoinQuantity)
        assertEquals(200, response.balance)
        assertNothingChanged()
    }

    @Test
    fun `읽었을 때는 생성 중이었지만 Worker가 먼저 완료했으면 취소하지 못하고 환불하지 않는다`() {
        givenReport(ReportStatus.GENERATING)
        every { reportGenerationJobRepository.findReportGenerationJobIdByReportId(reportId) } returns null
        every { reportRepository.updateReportStatuses(any(), any(), any(), any()) } returns 0
        every { reportRepository.findReportStatusWithLockById(reportId) } returns "COMPLETED"

        assertBusinessException(ReportErrorCode.NOT_CANCELABLE) { service.cancelReport(userId, reportId) }
        verify(exactly = 0) { reportPurchaseRefundService.createReportPurchaseRefund(any(), any(), any()) }
    }

    @Test
    fun `완료되었거나 실패한 리포트는 취소할 수 없다`() {
        listOf("COMPLETED", "FAILED").forEach { status ->
            givenReport(ReportStatus.valueOf(status))
            every { reportRepository.updateReportStatuses(any(), any(), any(), any()) } returns 0
            every { reportRepository.findReportStatusWithLockById(reportId) } returns status

            assertBusinessException(ReportErrorCode.NOT_CANCELABLE) { service.cancelReport(userId, reportId) }
        }
        verify(exactly = 0) { reportPurchaseRefundService.createReportPurchaseRefund(any(), any(), any()) }
    }

    @Test
    fun `다른 사용자의 리포트는 취소할 수 없고 아무것도 바꾸지 않는다`() {
        givenReport(ReportStatus.WAITING, ownerId = 2L)

        assertBusinessException(AuthErrorCode.FORBIDDEN) { service.cancelReport(userId, reportId) }
        assertNothingChanged()
    }

    @Test
    fun `없는 리포트는 취소할 수 없다`() {
        every { reportRepository.findById(reportId) } returns Optional.empty()

        assertBusinessException(ReportErrorCode.REPORT_NOT_FOUND) { service.cancelReport(userId, reportId) }
        assertNothingChanged()
    }
}
