package com.dandi.nyummy.report.repository

import com.dandi.nyummy.report.entity.Report
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * 대조 배치가 쓰는 불일치 조회. 모두 불일치가 있는 리포트 id를 [limit]개까지 id 순으로 돌려준다.
 * 상태 값은 문자열로 받는다(네이티브 쿼리라 enum 이름 그대로 비교한다).
 *
 * 정상이면 모두 빈 결과다. 리포트·구매가 많아지면 전체를 훑는 쿼리라 하루 한 번 정도로만 돌린다.
 */
interface ReportMismatchRepository : Repository<Report, Long> {

    @Query(
        value = """
            SELECT r.id
            FROM reports r
                JOIN report_purchases p ON p.report_id = r.id
                LEFT JOIN report_purchase_refunds rf ON rf.report_purchase_id = p.id
            WHERE p.price > 0
                AND r.status IN (:refundableStatuses)
                AND rf.id IS NULL
            ORDER BY r.id
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findReportIdsWithMissingRefund(
        @Param("refundableStatuses") refundableStatuses: Collection<String>,
        @Param("limit") limit: Int,
    ): List<Long>

    @Query(
        value = """
            SELECT r.id
            FROM report_purchase_refunds rf
                JOIN report_purchases p ON p.id = rf.report_purchase_id
                JOIN reports r ON r.id = p.report_id
            WHERE r.status NOT IN (:refundableStatuses)
                OR p.price = 0
            ORDER BY r.id
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findReportIdsWithUnexpectedRefund(
        @Param("refundableStatuses") refundableStatuses: Collection<String>,
        @Param("limit") limit: Int,
    ): List<Long>

    @Query(
        value = """
            SELECT p.report_id
            FROM report_purchase_refunds rf
                JOIN report_purchases p ON p.id = rf.report_purchase_id
            WHERE rf.coin_quantity <> p.price
            ORDER BY p.report_id
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findReportIdsWithRefundQuantityMismatch(@Param("limit") limit: Int): List<Long>

    // 코인 내역은 (user_id, id) 인덱스로 사용자별로 좁힌 뒤 종류·원천 id·수량을 맞춰 센다.
    @Query(
        value = """
            SELECT p.report_id
            FROM report_purchases p
            WHERE (
                SELECT COUNT(*)
                FROM coin_histories ch
                WHERE ch.user_id = p.user_id
                    AND ch.category = 'REPORT_PURCHASE'
                    AND ch.source_id = p.id
                    AND ch.quantity = -p.price
            ) <> CASE WHEN p.price > 0 THEN 1 ELSE 0 END
            ORDER BY p.report_id
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findReportIdsWithPurchaseCoinHistoryMismatch(@Param("limit") limit: Int): List<Long>

    @Query(
        value = """
            SELECT p.report_id
            FROM report_purchase_refunds rf
                JOIN report_purchases p ON p.id = rf.report_purchase_id
            WHERE (
                SELECT COUNT(*)
                FROM coin_histories ch
                WHERE ch.user_id = p.user_id
                    AND ch.category = 'REPORT_PURCHASE_REFUND'
                    AND ch.source_id = rf.id
                    AND ch.quantity = rf.coin_quantity
            ) <> 1
            ORDER BY p.report_id
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findReportIdsWithRefundCoinHistoryMismatch(@Param("limit") limit: Int): List<Long>

    @Query(
        value = """
            SELECT j.report_id
            FROM report_generation_jobs j
                JOIN reports r ON r.id = j.report_id
            WHERE r.status NOT IN (:inProgressStatuses)
            ORDER BY j.report_id
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findReportIdsWithOrphanJob(
        @Param("inProgressStatuses") inProgressStatuses: Collection<String>,
        @Param("limit") limit: Int,
    ): List<Long>

    @Query(
        value = """
            SELECT r.id
            FROM reports r
                LEFT JOIN report_generation_jobs j ON j.report_id = r.id
            WHERE r.status IN (:inProgressStatuses)
                AND j.id IS NULL
            ORDER BY r.id
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findReportIdsWithMissingJob(
        @Param("inProgressStatuses") inProgressStatuses: Collection<String>,
        @Param("limit") limit: Int,
    ): List<Long>

    @Query(
        value = """
            SELECT r.id
            FROM reports r
            WHERE r.status IN (:inProgressStatuses)
                AND r.created_at < :createdBefore
            ORDER BY r.id
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findReportIdsWithOverdueGeneration(
        @Param("inProgressStatuses") inProgressStatuses: Collection<String>,
        @Param("createdBefore") createdBefore: Instant,
        @Param("limit") limit: Int,
    ): List<Long>

    @Query(
        value = """
            SELECT r.id
            FROM reports r
            WHERE r.status = :completedStatus
                AND (r.title IS NULL OR r.view_type IS NULL OR r.content IS NULL OR r.completed_at IS NULL)
            ORDER BY r.id
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findReportIdsWithIncompleteCompleted(
        @Param("completedStatus") completedStatus: String,
        @Param("limit") limit: Int,
    ): List<Long>
}
