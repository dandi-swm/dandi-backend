package com.dandi.nyummy.report.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.ReportErrorCode
import com.dandi.nyummy.report.config.ReportProperties
import com.dandi.nyummy.report.entity.Report
import com.dandi.nyummy.report.enum.ReportStatus
import com.dandi.nyummy.report.repository.ReportPurchaseRefundRepository
import com.dandi.nyummy.report.repository.ReportPurchaseRepository
import com.dandi.nyummy.report.repository.ReportRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.SliceImpl
import org.springframework.test.util.ReflectionTestUtils
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportServiceTest {

    private val now = Instant.parse("2026-11-10T03:00:00Z")
    private val userId = 1L
    private val otherUserId = 2L
    private val sunday = LocalDate.parse("2026-11-01")

    private val reportRepository = mockk<ReportRepository>()
    private val reportPurchaseRepository = mockk<ReportPurchaseRepository>()
    private val reportPurchaseRefundRepository = mockk<ReportPurchaseRefundRepository>()

    private val service = ReportService(
        clock = Clock.fixed(now, ZoneOffset.UTC),
        objectMapper = JsonMapper.builder().build(),
        reportProperties = ReportProperties(listPrice = 800, minMealCount = 3),
        reportRepository = reportRepository,
        reportPurchaseRepository = reportPurchaseRepository,
        reportPurchaseRefundRepository = reportPurchaseRefundRepository,
    )

    init {
        every { reportPurchaseRepository.existsFreeReportPurchase(userId, ReportStatus.ACTIVE_STATUSES) } returns false
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    private fun report(id: Long, status: ReportStatus = ReportStatus.WAITING, ownerId: Long = userId) =
        Report(ownerId, sunday).apply {
            ReflectionTestUtils.setField(this, "id", id)
            this.status = status
        }

    private fun givenReports(reports: List<Report>, hasNext: Boolean) {
        every { reportRepository.findReportsByUserIdBeforeCursor(userId, any(), any()) } returns
            SliceImpl(reports, PageRequest.of(0, 2), hasNext)
    }

    // getReports

    @Test
    fun `목록에 지금 구매 가격과 최소 식사 수와 다음 cursor를 준다`() {
        givenReports(listOf(report(32), report(31)), hasNext = true)
        every { reportPurchaseRefundRepository.findRefundedReportIds(listOf(32L, 31L)) } returns emptyList()

        val response = service.getReports(userId, cursor = null, size = 2)

        assertEquals(0, response.purchasePrice)
        assertEquals(3, response.minMealCount)
        assertEquals(listOf(32L, 31L), response.reports.map { it.reportId })
        assertEquals(31L, response.nextCursor)
        assertTrue(response.hasNext)
        verify { reportRepository.findReportsByUserIdBeforeCursor(userId, Long.MAX_VALUE, PageRequest.of(0, 2)) }
    }

    @Test
    fun `무료로 산 리포트가 남아 있으면 목록의 구매 가격은 정가다`() {
        every { reportPurchaseRepository.existsFreeReportPurchase(userId, ReportStatus.ACTIVE_STATUSES) } returns true
        givenReports(emptyList(), hasNext = false)

        val response = service.getReports(userId, cursor = null, size = 10)

        assertEquals(800, response.purchasePrice)
        assertTrue(response.reports.isEmpty())
        assertNull(response.nextCursor)
        assertFalse(response.hasNext)
        verify(exactly = 0) { reportPurchaseRefundRepository.findRefundedReportIds(any()) }
    }

    @Test
    fun `환불된 리포트만 isRefunded이고 완료 뒤 안 본 리포트만 isUnread다`() {
        val completed = report(33, ReportStatus.COMPLETED)
        val read = report(32, ReportStatus.COMPLETED).apply { readAt = now }
        val canceled = report(31, ReportStatus.CANCELED)
        givenReports(listOf(completed, read, canceled), hasNext = false)
        every { reportPurchaseRefundRepository.findRefundedReportIds(listOf(33L, 32L, 31L)) } returns listOf(31L)

        val reports = service.getReports(userId, cursor = null, size = 10).reports.associateBy { it.reportId }

        assertEquals(listOf(true, false, false), listOf(33L, 32L, 31L).map { reports.getValue(it).isUnread })
        assertEquals(listOf(false, false, true), listOf(33L, 32L, 31L).map { reports.getValue(it).isRefunded })
        assertEquals(LocalDate.parse("2026-11-07"), reports.getValue(33L).endDate)
    }

    // getReport

    @Test
    fun `없는 리포트는 찾을 수 없다`() {
        every { reportRepository.findById(99L) } returns Optional.empty()

        assertBusinessException(ReportErrorCode.REPORT_NOT_FOUND) { service.getReport(userId, 99L) }
    }

    @Test
    fun `다른 사용자의 리포트는 조회할 수 없고 읽음으로 기록하지 않는다`() {
        val report = report(33, ReportStatus.COMPLETED, ownerId = otherUserId)
        every { reportRepository.findById(33L) } returns Optional.of(report)

        assertBusinessException(AuthErrorCode.FORBIDDEN) { service.getReport(userId, 33L) }
        assertNull(report.readAt)
    }

    @Test
    fun `완료된 리포트를 처음 조회하면 읽음으로 기록하고 저장된 뷰와 내용을 준다`() {
        val report = report(33, ReportStatus.COMPLETED).apply {
            title = "단백질이 아쉬운 한 주였어요"
            viewType = "NUTRITION_BALANCE"
            content = """{"mealCount":12}"""
            completedAt = now.minusSeconds(60)
        }
        every { reportRepository.findById(33L) } returns Optional.of(report)

        val response = service.getReport(userId, 33L)

        assertEquals(now, report.readAt)
        assertEquals("NUTRITION_BALANCE", response.viewType)
        assertEquals(12, response.content?.get("mealCount")?.asInt())
        assertEquals(now.minusSeconds(60), response.completedAt)
    }

    @Test
    fun `완료 전 리포트는 내용 없이 주고 읽음으로 기록하지 않는다`() {
        val report = report(33, ReportStatus.GENERATING)
        every { reportRepository.findById(33L) } returns Optional.of(report)

        val response = service.getReport(userId, 33L)

        assertNull(report.readAt)
        assertNull(response.viewType)
        assertNull(response.content)
        assertNull(response.completedAt)
    }
}
