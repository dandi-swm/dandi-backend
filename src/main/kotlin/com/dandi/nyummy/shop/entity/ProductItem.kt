package com.dandi.nyummy.shop.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

/**
 * 상품의 구성품. 단품은 한 행, 세트는 구성품 수만큼이다. 만든 뒤에는 바꾸지 않고, 구성을 바꾸려면 새 상품을 만든다.
 *
 * @property listPrice 구성품 정가. 세트를 일부만 환불할 때 남는 구성품을 정가로 계산하는 기준이다
 */
@Entity
@IdClass(ProductItemId::class)
@Table(name = "product_items")
class ProductItem(
    @Id
    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Id
    @Column(name = "item_id", nullable = false)
    val itemId: Long,

    @Column(name = "list_price", nullable = false)
    val listPrice: Int,
)

/**
 * [ProductItem]의 복합 키. 필드 이름과 타입이 엔티티의 @Id 필드와 같아야 한다.
 */
data class ProductItemId(val productId: Long = 0L, val itemId: Long = 0L) : Serializable
