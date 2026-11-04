package com.dandi.nyummy.shop.entity

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
 * 환불 한 번. 운영자가 CS로 처리하고, 구매 상품 1개를 여러 번 나눠 환불할 수 있다. 추가만 하고 수정·삭제하지 않는다.
 *
 * @property coinQuantity 이번에 돌려준 코인. 세트는 할인을 취소한 금액이라 구성품 정가 합과 다를 수 있다
 * @property reason 운영자가 적은 환불 이유
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "item_purchase_refunds")
class ItemPurchaseRefund(
    @Column(name = "item_purchase_product_id", nullable = false)
    val itemPurchaseProductId: Long,

    @Column(name = "admin_id", nullable = false)
    val adminId: Long,

    @Column(name = "coin_quantity", nullable = false)
    val coinQuantity: Int,

    @Column(name = "reason", nullable = false, length = 255)
    val reason: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
