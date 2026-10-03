package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.MealOutbox
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface MealOutboxRepository : JpaRepository<MealOutbox, Long> {
    @Query(
        value = "SELECT id FROM meal_outbox WHERE published_at IS NULL ORDER BY id LIMIT :limit",
        nativeQuery = true,
    )
    fun findPendingIds(limit: Int): List<Long>

    // 호출자의 트랜잭션이 큐 적재와 전달 완료 변경까지 포함해야 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM MealOutbox o WHERE o.id = :id")
    fun findByIdForUpdate(id: Long): MealOutbox?
}
