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
import java.time.Instant

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "analysis_feedbacks")
class AnalysisFeedback(
    @Column(name = "rating", nullable = false)
    val rating: Float,

    @Column(name = "reason", length = 500)
    val reason: String? = null,

    @Column(name = "meal_id", nullable = false, unique = true)
    val mealId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
