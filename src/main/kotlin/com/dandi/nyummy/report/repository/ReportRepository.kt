package com.dandi.nyummy.report.repository

import com.dandi.nyummy.report.entity.Report
import com.dandi.nyummy.report.enum.ReportStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface ReportRepository : JpaRepository<Report, Long> {

    @Query(
        """
        select r
        from Report as r
        where r.userId = :userId
            and r.id < :cursor
        order by r.id desc
    """,
    )
    fun findReportsByUserIdBeforeCursor(
        @Param("userId") userId: Long,
        @Param("cursor") cursor: Long,
        pageable: Pageable,
    ): Slice<Report>

    // 같은 주 중복 검사. idx_reports_user_id_start_date를 탄다.
    fun existsByUserIdAndStartDateAndStatusIn(
        userId: Long,
        startDate: LocalDate,
        statuses: Collection<ReportStatus>,
    ): Boolean
}
