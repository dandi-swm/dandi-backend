package com.dandi.nyummy.report.service

import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.report.entity.ReportPurchase
import com.dandi.nyummy.report.entity.ReportPurchaseRefund
import com.dandi.nyummy.report.enum.ReportPurchaseRefundCategory
import com.dandi.nyummy.report.repository.ReportPurchaseRefundRepository
import com.dandi.nyummy.report.repository.ReportPurchaseRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.test.util.ReflectionTestUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReportPurchaseRefundServiceTest {

    private val userId = 1L
    private val reportId = 10L

    private val coinService = mockk<CoinService>()
    private val reportPurchaseRepository = mockk<ReportPurchaseRepository>()
    private val reportPurchaseRefundRepository = mockk<ReportPurchaseRefundRepository>()

    private val service =
        ReportPurchaseRefundService(coinService, reportPurchaseRepository, reportPurchaseRefundRepository)

    private fun givenPurchase(price: Int) {
        every { reportPurchaseRepository.findByReportId(reportId) } returns
            ReportPurchase(userId, reportId, price, "key").also { ReflectionTestUtils.setField(it, "id", 100L) }
    }

    @Test
    fun `유료 구매면 환불 기록을 남기고 그 id로 코인을 돌려준다`() {
        givenPurchase(800)
        val savedRefund = slot<ReportPurchaseRefund>()
        every { reportPurchaseRefundRepository.saveAndFlush(capture(savedRefund)) } answers {
            firstArg<ReportPurchaseRefund>().also { ReflectionTestUtils.setField(it, "id", 500L) }
        }
        every { coinService.increaseBalance(userId, 800, CoinHistoryCategory.REPORT_PURCHASE_REFUND, 500L) } returns
            1000

        val refunded = service.createReportPurchaseRefund(userId, reportId, ReportPurchaseRefundCategory.CANCELED)

        assertEquals(800, refunded)
        assertEquals(100L, savedRefund.captured.reportPurchaseId)
        assertEquals(ReportPurchaseRefundCategory.CANCELED, savedRefund.captured.category)
        verify { coinService.increaseBalance(userId, 800, CoinHistoryCategory.REPORT_PURCHASE_REFUND, 500L) }
    }

    @Test
    fun `무료 구매면 돌려줄 코인이 없어 아무것도 남기지 않는다`() {
        givenPurchase(0)

        assertEquals(0, service.createReportPurchaseRefund(userId, reportId, ReportPurchaseRefundCategory.FAILED))
        verify(exactly = 0) { reportPurchaseRefundRepository.saveAndFlush(any()) }
        verify(exactly = 0) { coinService.increaseBalance(any(), any(), any(), any()) }
    }

    @Test
    fun `리포트의 구매가 없으면 데이터가 깨진 것이라 실패시킨다`() {
        every { reportPurchaseRepository.findByReportId(reportId) } returns null

        assertFailsWith<IllegalStateException> {
            service.createReportPurchaseRefund(userId, reportId, ReportPurchaseRefundCategory.FAILED)
        }
    }
}
