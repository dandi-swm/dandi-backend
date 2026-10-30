package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.MealAnalysisJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MealAnalysisJobRepository : JpaRepository<MealAnalysisJob, Long> {

    /**
     * Worker가 가져갈 작업을 [limit]개까지 잠가(SELECT … FOR UPDATE SKIP LOCKED) 조회한다.
     * 대기(lease_expires_at IS NULL)이거나 lease가 만료된(lease_expires_at < NOW()) 작업이 대상이다.
     *
     * lease_expires_at 인덱스 순서대로 읽다가 [limit]개에서 멈춘다. NULL이 인덱스 맨 앞이라 대기 작업이 먼저 나오고
     * (대기끼리는 id 순), 만료된 작업은 대기 작업이 다 빠진 뒤에 나온다. 다른 Worker가 잠근 행은 기다리지 않고 건너뛴다.
     *
     * 잠금은 호출한 트랜잭션이 끝날 때까지 유지되므로 [updateLease]와 같은 트랜잭션에서 불러야 한다.
     * 트랜잭션 밖에서 부르면 문장이 끝나자마자 잠금이 풀려 두 Worker가 같은 작업을 가져갈 수 있다.
     */
    @Query(
        value = """
            SELECT *
            FROM meal_analysis_jobs
            WHERE lease_expires_at IS NULL
                OR lease_expires_at < NOW()
            ORDER BY lease_expires_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun findMealAnalysisJobsWithLock(@Param("limit") limit: Int): List<MealAnalysisJob>

    /**
     * 작업들의 lease를 지금(DB 시계)부터 [leaseTimeToLiveSeconds]초 뒤까지로 잡고 lease_count를 1 올린다.
     * 올린 lease_count가 이 시도의 번호이고, 결과를 쓸 때 이 번호로 아직 내 작업인지 확인한다.
     *
     * 영속성 컨텍스트는 비우지 않으므로 [findMealAnalysisJobsWithLock]로 읽은 엔티티에는 올리기 전 값이 남는다.
     * 같은 트랜잭션에서 행을 잠근 채 올리므로 이 시도의 번호는 읽은 leaseCount + 1과 같다.
     */
    @Modifying
    @Query(
        value = """
            UPDATE meal_analysis_jobs
            SET lease_expires_at = NOW() + INTERVAL :leaseTimeToLiveSeconds SECOND,
                lease_count = lease_count + 1
            WHERE id IN (:ids)
        """,
        nativeQuery = true,
    )
    fun updateLease(
        @Param("ids") ids: Collection<Long>,
        @Param("leaseTimeToLiveSeconds") leaseTimeToLiveSeconds: Long,
    ): Int
}
