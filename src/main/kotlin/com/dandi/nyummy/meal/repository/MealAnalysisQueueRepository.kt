package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.MealAnalysisQueue
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface MealAnalysisQueueRepository : JpaRepository<MealAnalysisQueue, Long> {
    // READ_COMMITTED 트랜잭션에서 PROCESSING 변경까지 수행한다.
    // MySQL REPEATABLE_READ의 갭 락은 서로 다른 작업의 상태 변경도 대기시킬 수 있다.
    @Query(
        value = """
            SELECT * FROM meal_analysis_queue
            WHERE status = 'READY'
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun findReadyForUpdate(limit: Int): List<MealAnalysisQueue>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM MealAnalysisQueue q WHERE q.id = :id")
    fun findByIdForUpdate(id: Long): MealAnalysisQueue?
}
