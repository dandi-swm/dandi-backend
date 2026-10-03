package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.meal.enum.MealAnalysisQueueStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "meal_analysis_queue")
class MealAnalysisQueue(
    @Column(name = "outbox_id", nullable = false, unique = true)
    val outboxId: Long,

    @Column(name = "meal_id", nullable = false)
    val mealId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: MealAnalysisQueueStatus = MealAnalysisQueueStatus.READY
        protected set

    @Column(name = "started_at")
    var startedAt: Instant? = null
        protected set

    @Column(name = "finished_at")
    var finishedAt: Instant? = null
        protected set

    fun start(at: Instant) {
        check(status == MealAnalysisQueueStatus.READY)
        status = MealAnalysisQueueStatus.PROCESSING
        startedAt = at
    }

    fun complete(at: Instant) {
        check(status == MealAnalysisQueueStatus.PROCESSING)
        status = MealAnalysisQueueStatus.DONE
        finishedAt = at
    }

    fun fail(at: Instant) {
        check(status == MealAnalysisQueueStatus.PROCESSING)
        status = MealAnalysisQueueStatus.FAILED
        finishedAt = at
    }
}
