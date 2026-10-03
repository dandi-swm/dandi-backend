package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.Meal
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface MealRepository : JpaRepository<Meal, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Meal m WHERE m.id = :id")
    fun findByIdForUpdate(id: Long): Meal?

    @Query(
        """
        select m from Meal as m
        where m.userId = :userId
        AND m.deletedAt is null
        AND m.mealAt >= :start
        AND m.mealAt < :end
    """,
    )
    fun getMealsByUserIdAndPeriod(userId: Long, start: Instant, end: Instant): List<Meal>

    fun getMealByIdAndDeletedAtIsNull(mealId: Long): Meal?

    fun existsByImageKey(imageKey: String): Boolean
}
