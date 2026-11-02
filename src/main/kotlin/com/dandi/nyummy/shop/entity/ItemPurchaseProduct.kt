package com.dandi.nyummy.shop.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 구매 안의 상품 1개. 환불 단위라 같은 상품을 3개 사면 3행이다. 가격은 구매 시점 값이다.
 *
 * @property promotionId 적용된 프로모션. null이면 정가로 샀다. 계산에는 쓰지 않고 프로모션별 매출과 CS 확인에 쓴다
 * @property listPrice 1개 정가
 * @property purchasePrice 1개 결제가
 */
@Entity
@Table(name = "item_purchase_products")
class ItemPurchaseProduct(
    @Column(name = "item_purchase_id", nullable = false)
    val itemPurchaseId: Long,

    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Column(name = "promotion_id")
    val promotionId: Long?,

    @Column(name = "list_price", nullable = false)
    val listPrice: Int,

    @Column(name = "price", nullable = false)
    val purchasePrice: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L
}
