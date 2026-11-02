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
 * 상점 구매 한 번. 추가만 하고 수정·삭제하지 않는다.
 *
 * @property purchasePrice 결제 총액(코인 차감액)
 * @property idempotencyKey 앱이 구매 버튼을 누를 때 만든 키. 같은 키로 다시 오면 처음 결과를 돌려준다
 * @property createdAt 구매 시각. 환불 기한의 기준이다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "item_purchases")
class ItemPurchase(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

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
