package com.dandi.nyummy.shop.entity

import com.dandi.nyummy.shop.enum.ItemPurchaseOutcome
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * 구매 시도 기록. 성공·실패 모두 한 행이고, 추가만 하고 수정·삭제하지 않는다.
 *
 * @property requestedProducts 앱이 보낸 상품 목록(JSON). 없는 상품 id도 그대로 남긴다
 * @property requestedTotalPrice 앱이 보낸 1개 결제가 × 수량의 합
 * @property itemPurchaseId 만들거나 다시 돌려준 구매. 거부되었으면 null이다
 * @property appVersion 앱 버그와 조작된 요청을 구분하는 데 쓴다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "item_purchase_attempts")
class ItemPurchaseAttempt(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requested_products", nullable = false)
    val requestedProducts: String,

    @Column(name = "requested_total_price", nullable = false)
    val requestedTotalPrice: Int,

    @Column(name = "idempotency_key", nullable = false, length = 64)
    val idempotencyKey: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    val outcome: ItemPurchaseOutcome,

    @Column(name = "item_purchase_id")
    val itemPurchaseId: Long? = null,

    @Column(name = "app_version", length = 20)
    val appVersion: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
