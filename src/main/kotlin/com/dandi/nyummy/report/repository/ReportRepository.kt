package com.dandi.nyummy.report.repository

import com.dandi.nyummy.report.entity.Report
import com.dandi.nyummy.report.enum.ReportStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
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

    // SELECT … FOR UPDATE. 생성 결과를 확정할 때 쓴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findReportWithLockById(id: Long): Report?

    /**
     * 리포트가 [fromStatuses] 중 하나일 때만 [status]로 바꾼다. 영속성 컨텍스트를 거치지 않고 status·updated_at만 쓴다.
     * 바뀐 행 수로 전이가 실제로 일어났는지 안다(이미 끝난 리포트는 바뀌지 않는다).
     */
    @Modifying
    @Query(
        """
        update Report r
        set r.status = :status, r.updatedAt = :updatedAt
        where r.id in :ids
            and r.status in :fromStatuses
    """,
    )
    fun updateReportStatuses(
        @Param("ids") ids: Collection<Long>,
        @Param("fromStatuses") fromStatuses: Collection<ReportStatus>,
        @Param("status") status: ReportStatus,
        @Param("updatedAt") updatedAt: Instant,
    ): Int
}
