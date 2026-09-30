package com.dandi.nyummy.cat.entity

import com.dandi.nyummy.cat.enum.CatWeight
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "cat")
class Cat(

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "user_id", nullable = false, unique = true)
    var userId: Long,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0L

    @Column(name = "love", nullable = false)
    var love: Int = 0

    @Column(name = "exp", nullable = false)
    var exp: Int = 0

    @Column(name = "weight", nullable = false, columnDefinition = "tinyint")
    var weight: Int = 0
        protected set

    @Column(name = "weight_updated_at", nullable = false)
    var weightUpdatedAt: Instant = Instant.now()
        protected set

    @Column(name = "last_meal_at", nullable = false)
    var lastMealAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()

    fun setWeight(step: Int, evaluatedAt: Instant) {
        this.weight = (this.weight + step).coerceIn(CatWeight.MIN_WEIGHT, CatWeight.MAX_WEIGHT)
        this.weightUpdatedAt = evaluatedAt
    }
}
