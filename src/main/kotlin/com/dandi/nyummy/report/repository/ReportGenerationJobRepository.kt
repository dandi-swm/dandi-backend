package com.dandi.nyummy.report.repository

import com.dandi.nyummy.report.entity.ReportGenerationJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * 리포트 생성 작업 큐. 구조와 규칙은 [com.dandi.nyummy.meal.repository.MealAnalysisJobRepository]와 같다.
 */
@Repository
interface ReportGenerationJobRepository : JpaRepository<ReportGenerationJob, Long> {

    /**
     * Worker가 가져갈 작업을 [limit]개까지 잠가(SELECT … FOR UPDATE SKIP LOCKED) 조회한다.
     * 대기(lease_expires_at IS NULL)이거나 lease가 만료된(lease_expires_at < NOW()) 작업이 대상이다.
     * 재시도를 미뤄 둔 작업(lease_expires_at이 미래)은 그 시각이 지나야 나온다.
     *
     * 잠금은 호출한 트랜잭션이 끝날 때까지 유지되므로 [updateLease]와 같은 트랜잭션에서 불러야 한다.
     */
    @Query(
        value = """
            SELECT *
            FROM report_generation_jobs
            WHERE lease_expires_at IS NULL
                OR lease_expires_at < NOW()
            ORDER BY lease_expires_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun findReportGenerationJobsWithLock(@Param("limit") limit: Int): List<ReportGenerationJob>

    /**
     * 작업들의 lease를 지금(DB 시계)부터 [leaseTimeToLiveSeconds]초 뒤까지로 잡고 lease_count를 1 올린다.
     * 같은 트랜잭션에서 행을 잠근 채 올리므로 이 시도의 번호는 읽은 leaseCount + 1과 같다.
     */
    @Modifying
    @Query(
        value = """
            UPDATE report_generation_jobs
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
     * 작업이 아직 [leaseCount]번째 시도일 때만 지운다. 지운 행 수가 0이면 lease가 만료돼 다른 Worker가 가져갔거나,
     * 사용자가 취소했거나, 이미 끝난 작업이다. 한 문장이라 확인과 삭제 사이에 다른 트랜잭션이 끼어들 수 없다.
     */
    @Modifying
    @Query("delete from ReportGenerationJob j where j.id = :id and j.leaseCount = :leaseCount")
    fun deleteByIdAndLeaseCount(@Param("id") id: Long, @Param("leaseCount") leaseCount: Int): Int

    /**
     * 작업이 아직 [leaseCount]번째 시도일 때만 지금(DB 시계)부터 [delaySeconds]초가 지나면 다시 가져갈 수 있게 둔다.
     * lease_count는 그대로 두어 이 시도를 쓴 것으로 센다. 바뀐 행 수가 0이면 이 Worker의 작업이 아니다.
     */
    @Modifying
    @Query(
        value = """
            UPDATE report_generation_jobs
            SET lease_expires_at = NOW() + INTERVAL :delaySeconds SECOND
            WHERE id = :id
                AND lease_count = :leaseCount
        """,
        nativeQuery = true,
    )
    fun retryLease(
        @Param("id") id: Long,
        @Param("leaseCount") leaseCount: Int,
        @Param("delaySeconds") delaySeconds: Long,
    ): Int

    /**
     * 작업이 아직 [leaseCount]번째 시도일 때만 lease_count를 하나 내려 시도 횟수를 쓰지 않게 하고,
     * 지금(DB 시계)부터 [delaySeconds]초가 지나면 다시 가져갈 수 있게 둔다. 바뀐 행 수가 0이면 이 Worker의 작업이 아니다.
     */
    @Modifying
    @Query(
        value = """
            UPDATE report_generation_jobs
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

    /** Worker가 지금 가져갈 수 있는 작업 수. [findReportGenerationJobsWithLock]의 조건과 같다. */
    @Query(
        value = """
            SELECT COUNT(*)
            FROM report_generation_jobs
            WHERE lease_expires_at IS NULL
                OR lease_expires_at < NOW()
        """,
        nativeQuery = true,
    )
    fun countWaitingReportGenerationJobs(): Long

    /** 가져갈 수 있는 작업 중 가장 오래된 것이 쌓인 뒤 지난 초(DB 시계). 그런 작업이 없으면 null. */
    @Query(
        value = """
            SELECT TIMESTAMPDIFF(SECOND, MIN(created_at), NOW())
            FROM report_generation_jobs
            WHERE lease_expires_at IS NULL
                OR lease_expires_at < NOW()
        """,
        nativeQuery = true,
    )
    fun findOldestWaitingSeconds(): Long?
}
