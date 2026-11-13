package com.dandi.nyummy.meal.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.math.BigDecimal
import java.time.Instant

/**
 * AI가 처음 인식한 음식 한 줄. 첫 인식의 이름과 인분만 담고, 한 번 쓰면 바꾸지 않는다.
 *
 * @property displayOrder 인식이 낸 순서. 1이 대표 음식이다
 * @property portion 인분
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "meal_food_snapshots")
class MealFoodSnapshot(
    @Column(name = "meal_snapshot_id", nullable = false)
    val mealSnapshotId: Long,

    @Column(name = "display_order", nullable = false)
    val displayOrder: Int,

    @Column(name = "name", nullable = false, length = 30)
    val name: String,

    @Column(name = "portion", nullable = false, precision = 2, scale = 1)
    val portion: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
