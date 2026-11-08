package com.dandi.nyummy.report.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * 리포트 구매 한 번. 리포트 하나에 하나이고, 추가만 하고 수정·삭제하지 않는다.
 *
 * @property purchasePrice 구매 시점에 낸 코인(0이면 첫 무료). 환불할 코인이기도 하다
 * @property idempotencyKey 앱이 받을 주를 골랐을 때 만든 키. 같은 키로 다시 오면 처음 결과를 돌려준다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "report_purchases")
class ReportPurchase(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "report_id", nullable = false, unique = true)
    val reportId: Long,

    @Column(name = "price", nullable = false)
    val purchasePrice: Int,

    @Column(name = "idempotency_key", nullable = false, length = 64)
    val idempotencyKey: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
