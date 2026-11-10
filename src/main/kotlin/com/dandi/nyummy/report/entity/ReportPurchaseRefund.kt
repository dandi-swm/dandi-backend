package com.dandi.nyummy.report.entity

import com.dandi.nyummy.report.enum.ReportPurchaseRefundCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * 리포트 구매 환불. 유료 구매의 리포트가 취소되거나 생성에 실패해 코인을 돌려준 기록이다.
 * 무료 구매는 돌려줄 코인이 없어 행이 없다. 추가만 하고 수정·삭제하지 않는다.
 *
 * 구매 하나는 한 번만 환불한다(report_purchase_id 유니크). 취소와 실패가 겹쳐도 두 번째 환불은 DB에서 막힌다.
 *
 * @property coinQuantity 돌려준 코인. 지금은 구매 가격과 같다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "report_purchase_refunds")
class ReportPurchaseRefund(
    @Column(name = "report_purchase_id", nullable = false, unique = true)
    val reportPurchaseId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    val category: ReportPurchaseRefundCategory,

    @Column(name = "coin_quantity", nullable = false)
    val coinQuantity: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
