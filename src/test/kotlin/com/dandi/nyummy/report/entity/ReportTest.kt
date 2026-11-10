package com.dandi.nyummy.report.entity

import com.dandi.nyummy.report.enum.ReportStatus
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReportTest {

    private val readAt = Instant.parse("2026-11-10T03:00:00Z")

    private fun report(status: ReportStatus) = Report(userId = 1L, startDate = LocalDate.parse("2026-11-01")).also {
        it.status = status
    }

    // updateReadAt

    @Test
    fun `완료된 리포트를 처음 읽으면 읽은 시각을 기록한다`() {
        val report = report(ReportStatus.COMPLETED)

        report.updateReadAt(readAt)

        assertEquals(readAt, report.readAt)
    }

    @Test
    fun `이미 읽은 리포트는 처음 읽은 시각을 그대로 둔다`() {
        val report = report(ReportStatus.COMPLETED)
        report.updateReadAt(readAt)

        report.updateReadAt(readAt.plusSeconds(60))

        assertEquals(readAt, report.readAt)
    }

    @Test
    fun `완료되지 않은 리포트는 읽음으로 기록하지 않는다`() {
        ReportStatus.entries.filter { it != ReportStatus.COMPLETED }.forEach { status ->
            val report = report(status)

            report.updateReadAt(readAt)

            assertNull(report.readAt, "$status 리포트를 읽음으로 기록했습니다")
        }
    }

    // confirm

    @Test
    fun `생성 중인 리포트를 확정하면 완료가 되고 결과를 채운다`() {
        val report = report(ReportStatus.GENERATING)

        report.confirm("제목", "NutritionBalance", "{}", readAt)

        assertEquals(ReportStatus.COMPLETED, report.status)
        assertEquals("제목", report.title)
        assertEquals("NutritionBalance", report.viewType)
        assertEquals("{}", report.content)
        assertEquals(readAt, report.completedAt)
    }

    @Test
    fun `생성 중이 아닌 리포트는 확정할 수 없다`() {
        ReportStatus.entries.filter { it != ReportStatus.GENERATING }.forEach { status ->
            assertFailsWith<IllegalStateException> { report(status).confirm("제목", "NutritionBalance", "{}", readAt) }
        }
    }
}
