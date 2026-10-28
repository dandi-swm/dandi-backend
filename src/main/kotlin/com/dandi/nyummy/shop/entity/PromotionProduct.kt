package com.dandi.nyummy.shop.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

/**
 * 프로모션에 묶인 상품.
 *
 * @property displayOrder 프로모션 화면의 진열 순서. 작은 값이 앞이다
 */
@Entity
@IdClass(PromotionProductId::class)
@Table(name = "promotion_products")
class PromotionProduct(
    @Id
    @Column(name = "promotion_id", nullable = false)
    val promotionId: Long,

    @Id
    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Column(name = "display_order", nullable = false)
    val displayOrder: Int,
)

/**
 * [PromotionProduct]의 복합 키. 필드 이름과 타입이 엔티티의 @Id 필드와 같아야 한다.
 */
data class PromotionProductId(val promotionId: Long = 0L, val productId: Long = 0L) : Serializable
