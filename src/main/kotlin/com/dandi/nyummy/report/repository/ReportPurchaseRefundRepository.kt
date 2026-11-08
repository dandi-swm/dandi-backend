package com.dandi.nyummy.report.repository

import com.dandi.nyummy.report.entity.ReportPurchaseRefund
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ReportPurchaseRefundRepository : JpaRepository<ReportPurchaseRefund, Long> {

    fun findByReportPurchaseId(reportPurchaseId: Long): ReportPurchaseRefund?

    /**
     * [reportIds] 중 환불된 구매의 리포트 id. 목록의 isRefunded에 쓴다.
     */
    @Query(
        """
        select p.reportId
        from ReportPurchaseRefund as rf
            join ReportPurchase as p on p.id = rf.reportPurchaseId
        where p.reportId in :reportIds
    """,
    )
    fun findRefundedReportIds(@Param("reportIds") reportIds: Collection<Long>): List<Long>
}
