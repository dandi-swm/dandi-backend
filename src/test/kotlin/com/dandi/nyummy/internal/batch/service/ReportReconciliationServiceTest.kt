package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.report.config.ReportProperties
import com.dandi.nyummy.report.enum.ReportMismatchCategory
import com.dandi.nyummy.report.repository.ReportMismatchRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportReconciliationServiceTest {

    private val now = Instant.parse("2026-11-10T20:00:00Z")
    private val reportMismatchRepository = mockk<ReportMismatchRepository>()
    private val meterRegistry = SimpleMeterRegistry()

    private val service = ReportReconciliationService(
        reportMismatchRepository = reportMismatchRepository,
        reportProperties = ReportProperties(generationTimeToLive = Duration.ofHours(1)),
        meterRegistry = meterRegistry,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    init {
        every { reportMismatchRepository.findReportIdsWithMissingRefund(any(), any()) } returns emptyList()
        every { reportMismatchRepository.findReportIdsWithUnexpectedRefund(any(), any()) } returns emptyList()
        every { reportMismatchRepository.findReportIdsWithRefundQuantityMismatch(any()) } returns emptyList()
        every { reportMismatchRepository.findReportIdsWithPurchaseCoinHistoryMismatch(any()) } returns emptyList()
        every { reportMismatchRepository.findReportIdsWithRefundCoinHistoryMismatch(any()) } returns emptyList()
        every { reportMismatchRepository.findReportIdsWithOrphanJob(any(), any()) } returns emptyList()
        every { reportMismatchRepository.findReportIdsWithMissingJob(any(), any()) } returns emptyList()
        every { reportMismatchRepository.findReportIdsWithOverdueGeneration(any(), any(), any()) } returns emptyList()
        every { reportMismatchRepository.findReportIdsWithIncompleteCompleted(any(), any()) } returns emptyList()
    }

    private fun countMismatches(category: ReportMismatchCategory): Double = meterRegistry
        .get("nyummy.report.reconciliation.mismatches")
        .tag("category", category.name.lowercase())
        .counter()
        .count()

    @Test
    fun `불일치가 없으면 모든 종류를 0건으로 준다`() {
        val response = service.reconcileReports()

        assertEquals(0, response.mismatchCount)
        assertEquals(ReportMismatchCategory.entries.toList(), response.mismatches.map { it.category })
        assertEquals(now, response.checkedAt)
    }

    @Test
    fun `찾은 불일치를 종류별로 모아 합계와 지표로 남긴다`() {
        every { reportMismatchRepository.findReportIdsWithMissingRefund(any(), any()) } returns listOf(3L, 7L)
        every { reportMismatchRepository.findReportIdsWithOrphanJob(any(), any()) } returns listOf(9L)

        val response = service.reconcileReports()

        assertEquals(3, response.mismatchCount)
        assertEquals(
            listOf(3L, 7L),
            response.mismatches.first {
                it.category == ReportMismatchCategory.MISSING_REFUND
            }.reportIds,
        )
        assertEquals(2.0, countMismatches(ReportMismatchCategory.MISSING_REFUND))
        assertEquals(1.0, countMismatches(ReportMismatchCategory.ORPHAN_JOB))
        assertEquals(0.0, countMismatches(ReportMismatchCategory.MISSING_JOB))
    }

    @Test
    fun `환불이 있어야 하는 상태는 취소·실패이고 생성 기한은 지금에서 기한을 뺀 시각이다`() {
        service.reconcileReports()

        verify { reportMismatchRepository.findReportIdsWithMissingRefund(listOf("CANCELED", "FAILED"), 100) }
        verify { reportMismatchRepository.findReportIdsWithMissingJob(listOf("WAITING", "GENERATING"), 100) }
        verify {
            reportMismatchRepository.findReportIdsWithOverdueGeneration(
                listOf("WAITING", "GENERATING"),
                now.minus(Duration.ofHours(1)),
                100,
            )
        }
    }
}
