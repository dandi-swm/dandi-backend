package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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
    fun findMealsByUserIdAndPeriod(userId: Long, start: Instant, end: Instant): List<Meal>

    fun findMealByIdAndDeletedAtIsNull(mealId: Long): Meal?

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

    /**
     * 식사들의 분석 상태만 바꾼다. 영속성 컨텍스트를 거치지 않고 status·updated_at 두 컬럼만 쓴다.
     *
     * 엔티티를 고쳐 커밋하면 Hibernate가 모든 컬럼을 다시 쓰므로, 그사이 사용자가 바꾼 이름 같은 값을 옛 값으로 덮을 수 있다.
     */
    @Modifying
    @Query("update Meal m set m.status = :status, m.updatedAt = :updatedAt where m.id in :mealIds")
    fun updateMealStatuses(
        @Param("mealIds") mealIds: Collection<Long>,
        @Param("status") status: MealStatus,
        @Param("updatedAt") updatedAt: Instant,
    ): Int
}
