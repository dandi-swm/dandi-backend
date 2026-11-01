package com.dandi.nyummy.shop.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * 장바구니 한 항목. 같은 상품은 한 행이고 수량으로 센다(uk_cart_items_user_id_product_id).
 *
 * 가격은 저장하지 않는다. 조회할 때마다 그 시점의 결제가로 계산하고, 구매 때 앱이 보낸 가격으로 사용자가 본 가격인지 다시 맞춘다.
 *
 * @property quantity 담은 수량. 상한(상품당·장바구니 전체)은 서비스가 검증한다
 * @property isSelected 결제 정보에 합산할지. 담으면 선택 상태가 된다
 * @property createdAt 담은 시각. 장바구니는 이 시각 최신순이다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "cart_items")
class CartItem(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Column(name = "quantity", nullable = false)
    var quantity: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Column(name = "is_selected", nullable = false)
    var isSelected: Boolean = true

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    fun updateQuantity(quantity: Int) {
        this.quantity = quantity
    }

    fun updateIsSelected(isSelected: Boolean) {
        this.isSelected = isSelected
    }
}
