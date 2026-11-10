package com.dandi.nyummy.report.entity

import com.dandi.nyummy.report.enum.ReportStatus
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
