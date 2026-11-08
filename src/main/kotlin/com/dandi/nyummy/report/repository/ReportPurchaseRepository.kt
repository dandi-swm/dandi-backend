package com.dandi.nyummy.report.repository

import com.dandi.nyummy.report.entity.ReportPurchase
import com.dandi.nyummy.report.enum.ReportStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ReportPurchaseRepository : JpaRepository<ReportPurchase, Long> {

    fun findByUserIdAndIdempotencyKey(userId: Long, idempotencyKey: String): ReportPurchase?

    fun findByReportId(reportId: Long): ReportPurchase?

    /**
     * 리포트가 [statuses] 중 하나인 무료(0코인) 구매가 있는지 확인한다. 첫 무료 판정에 쓴다.
     */
    @Query(
        """
        select count(p) > 0
        from ReportPurchase as p
            join Report as r on r.id = p.reportId
        where p.userId = :userId
            and p.purchasePrice = 0
            and r.status in :statuses
    """,
    )
    fun existsFreeReportPurchase(
        @Param("userId") userId: Long,
        @Param("statuses") statuses: Collection<ReportStatus>,
    ): Boolean
}
