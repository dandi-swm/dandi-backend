package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface MealRepository : JpaRepository<Meal, Long> {

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

    @Query(
        """
        select COUNT(m) > 0
        from Meal as m
        where m.userId = :userId
            and m.deletedAt is null
            and m.status = :status
            and m.mealAt > :mealAt
    """,
    )
    fun existsCompletedMealAfter(
        @Param("userId") userId: Long,
        @Param("status") status: MealStatus,
        @Param("mealAt") mealAt: Instant,
    ): Boolean

    @Query(
        """
            select count(m)
            from Meal as m
            where m.userId = :userId
                and m.mealAt >= :start
                and m.mealAt < :end
        """,
    )
    fun countMealsByUserIdAndPeriod(
        @Param("userId") userId: Long,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): Long
}
