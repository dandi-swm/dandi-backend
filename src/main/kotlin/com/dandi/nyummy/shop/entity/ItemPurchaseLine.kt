package com.dandi.nyummy.shop.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 구매한 상품 1개 안의 구성품 하나. 단품은 1행, 세트는 구성품 수만큼이다.
 * 구매 우편의 아이템과 받은 뒤의 보유 아이템이 이 행을 가리키고, 환불은 이 행 단위로 고른다.
 *
 * @property listPrice 구매 시점 구성품 정가. 세트를 일부만 환불할 때 남는 구성품을 계산하는 기준이다
 */
@Entity
@Table(name = "item_purchase_lines")
class ItemPurchaseLine(
    @Column(name = "item_purchase_product_id", nullable = false)
    val itemPurchaseProductId: Long,

    @Column(name = "item_id", nullable = false)
    val itemId: Long,

    @Column(name = "list_price", nullable = false)
    val listPrice: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L
}
