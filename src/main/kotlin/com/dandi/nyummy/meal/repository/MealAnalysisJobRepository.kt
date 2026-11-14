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

    /**
     * 작업이 아직 [leaseCount]번째 시도일 때만 지운다. 지운 행 수가 0이면 lease가 만료돼 다른 Worker가 가져갔거나 이미 끝난 작업이다.
     *
     * 조회 후 삭제하는 파생 메서드와 달리 한 문장이라, 확인과 삭제 사이에 다른 Worker가 끼어들 수 없다.
     */
    @Modifying
    @Query("delete from MealAnalysisJob j where j.id = :id and j.leaseCount = :leaseCount")
    fun deleteByIdAndLeaseCount(@Param("id") id: Long, @Param("leaseCount") leaseCount: Int): Int

    /**
     * 식사의 작업 id. 작업이 없으면 null. 잠그지 않고 읽는다.
     *
     * 식사의 작업을 지울 때 `meal_id`로 바로 지우지 않고 이 id로 지운다([deleteMealAnalysisJobById]).
     * 없는 행을 `meal_id`로 지우려 하면 InnoDB가 유니크 인덱스의 빈 구간을 잠그는데, 이 테이블은 거의 비어 있어 그 구간이
     * 테이블 전체가 된다. 그러면 서로 다른 식사의 요청 둘이 겹쳤을 때 서로의 INSERT를 막아 데드락이 난다.
     */
    @Query("select j.id from MealAnalysisJob j where j.mealId = :mealId")
    fun findIdByMealId(@Param("mealId") mealId: Long): Long?

    /**
     * 작업을 id로 지운다. 사용자가 확인을 기다리던 식사의 음식 목록을 보내거나 식사를 지울 때 기다리던 작업을 없앤다.
     * 식사를 지울 때 그 작업을 처리하던 Worker가 있으면, 결과를 쓸 때 자기 행이 없어 결과를 버린다.
     *
     * 조회 후 삭제하는 파생 메서드와 달리 바로 실행되는 한 문장이라, 같은 트랜잭션에서 이어서 새 작업을 저장해도
     * `meal_id` 유니크 제약에 걸리지 않는다.
     */
    @Modifying
    @Query("delete from MealAnalysisJob j where j.id = :id")
    fun deleteMealAnalysisJobById(@Param("id") id: Long): Int

    /**
     * 작업이 아직 [leaseCount]번째 시도일 때만 lease를 풀어 대기로 되돌린다. 바뀐 행 수가 0이면 이 Worker의 작업이 아니다.
     * lease_count는 그대로 두어, 다음에 가져갈 때 다음 번호가 된다.
     */
    @Modifying
    @Query("update MealAnalysisJob j set j.leaseExpiresAt = null where j.id = :id and j.leaseCount = :leaseCount")
    fun resetLease(@Param("id") id: Long, @Param("leaseCount") leaseCount: Int): Int

    /**
     * 작업이 아직 [leaseCount]번째 시도일 때만 lease_count를 하나 내려 시도 횟수를 쓰지 않게 하고,
     * 지금(DB 시계)부터 [delaySeconds]초가 지나면 다시 가져갈 수 있게 둔다. 0이면 다음 polling에서 바로 가져간다.
     * 바뀐 행 수가 0이면 이 Worker의 작업이 아니다.
     */
    @Modifying
    @Query(
        value = """
            UPDATE meal_analysis_jobs
            SET lease_expires_at = NOW() + INTERVAL :delaySeconds SECOND,
                lease_count = lease_count - 1
            WHERE id = :id
                AND lease_count = :leaseCount
        """,
        nativeQuery = true,
    )
    fun cancelLease(
        @Param("id") id: Long,
        @Param("leaseCount") leaseCount: Int,
        @Param("delaySeconds") delaySeconds: Long,
    ): Int

    /**
     * 작업을 지금(DB 시계)부터 [delaySeconds]초가 지나야 가져갈 수 있게 미루고, 그 시각을 available_at에 남긴다.
     * lease_count는 그대로 두므로 시도 횟수를 쓰지 않고, 그 시간이 지나 처음 가져갈 때 첫 번째 시도가 된다.
     *
     * 막 만든 작업을 일정 시간 뒤부터 처리하게 할 때 쓴다. v2 영양 작업은 사용자가 인식된 음식 목록을 보고 고칠 시간을 둔다.
     * lease_expires_at은 Worker가 가져갈 때 덮이므로, 지표가 기다린 시간을 셀 기준은 available_at에 따로 둔다.
     */
    @Modifying
    @Query(
        value = """
            UPDATE meal_analysis_jobs
            SET lease_expires_at = NOW() + INTERVAL :delaySeconds SECOND,
                available_at = NOW() + INTERVAL :delaySeconds SECOND
            WHERE id = :id
        """,
        nativeQuery = true,
    )
    fun updateAvailableAt(@Param("id") id: Long, @Param("delaySeconds") delaySeconds: Long): Int

    /**
     * Worker가 지금 가져갈 수 있는 작업 수. 대기이거나 lease가 만료된 작업이며, [findMealAnalysisJobsWithLock]의 조건과 같다.
     * 처리 중인 작업과 미뤄 둔 작업(lease_expires_at이 미래)은 세지 않는다. 설정 오류로 잠시 미룬 작업과,
     * 사용자가 음식 목록을 고칠 시간을 기다리는 v2 영양 작업이 그렇다.
     */
    @Query(
        value = """
            SELECT COUNT(*)
            FROM meal_analysis_jobs
            WHERE lease_expires_at IS NULL
                OR lease_expires_at < NOW()
        """,
        nativeQuery = true,
    )
    fun countWaitingMealAnalysisJobs(): Long

    /**
     * 가져갈 수 있는 작업 중 가장 오래 기다린 것이 기다린 초(DB 시계). 그런 작업이 없으면 null.
     * 쌓인 때부터 세되, 만들 때 미룬 작업은 가져갈 수 있게 된 때(available_at)부터 센다. 일부러 기다리게 한 시간은 빼려는 것이다.
     * 재시도로 대기에 돌아온 작업은 처음 기준 그대로 센다.
     */
    @Query(
        value = """
            SELECT TIMESTAMPDIFF(SECOND, MIN(COALESCE(available_at, created_at)), NOW())
            FROM meal_analysis_jobs
            WHERE lease_expires_at IS NULL
                OR lease_expires_at < NOW()
        """,
        nativeQuery = true,
    )
    fun findOldestWaitingSeconds(): Long?
}
