package com.dandi.nyummy.meal.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "meal_outbox")
class MealOutbox(
    @Column(name = "meal_id", nullable = false)
    val mealId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Column(name = "published_at")
    var publishedAt: Instant? = null
        protected set

    fun markPublished(at: Instant) {
        publishedAt = at
    }
}
