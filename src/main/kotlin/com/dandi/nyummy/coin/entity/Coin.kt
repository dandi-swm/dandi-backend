package com.dandi.nyummy.coin.entity

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
 * 사용자의 코인 잔액. 사용자당 하나다.
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "coins")
class Coin(
    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Column(name = "balance", nullable = false)
    var balance: Int = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    /**
     * 잔액을 [quantity]만큼 늘린다. 지급 수량은 서버가 정한 값이므로 0 이하면 데이터 오류로 본다.
     */
    fun increaseBalance(quantity: Int) {
        require(quantity > 0) { "늘릴 코인 수는 1 이상이어야 합니다: $quantity" }
        balance += quantity
    }
}
