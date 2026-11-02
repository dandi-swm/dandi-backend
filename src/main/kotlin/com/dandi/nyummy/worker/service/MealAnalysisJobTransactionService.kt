package com.dandi.nyummy.worker.service

import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.worker.config.ConditionalOnWorkerEnabled
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Worker가 쓰는 짧은 트랜잭션을 모은다. Gemini 호출처럼 오래 걸리는 일은 이 트랜잭션들 밖에서 한다.
 *
 * 잠그는 순서는 작업(meal_analysis_jobs) → 식사(meals)로 고정한다. 반대 순서로 잠그는 트랜잭션이 있으면 서로를 기다려 데드락이 난다.
 */
@Service
@ConditionalOnWorkerEnabled
class MealAnalysisJobTransactionService(
    private val mealAnalysisJobRepository: MealAnalysisJobRepository,
    private val mealRepository: MealRepository,
    private val workerProperties: WorkerProperties,
    private val clock: Clock,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(MealAnalysisJobTransactionService::class.java)
    }

    /**
     * 대기 중이거나 lease가 만료된 작업을 [limit]개까지 lease로 가져가고, 그 식사들을 ANALYZING으로 바꾼다.
     *
     * 작업 행을 잠근 채(SKIP LOCKED) lease와 lease_count를 올리므로, 같은 작업을 두 Worker가 함께 가져가지 않는다.
     * 돌려주는 leaseCount는 읽은 값 + 1로, 커밋된 lease_count와 같다.
     *
     * 그사이 사용자가 지운 식사의 작업은 지우고 돌려주지 않는다. 분석하지 않으므로 Gemini 비용과 알림이 생기지 않는다.
     *
     * @param limit 이번에 가져갈 최대 개수 (Worker의 빈 자리 수)
     * @return 가져간 작업. 가져갈 작업이 없으면 빈 목록
     */
    @Transactional
    fun leaseMealAnalysisJobs(limit: Int): List<MealAnalysisJobResult> {
        val jobs = mealAnalysisJobRepository.findMealAnalysisJobsWithLock(limit)
        if (jobs.isEmpty()) {
            return emptyList()
        }

        val mealsById = mealRepository.findAllById(jobs.map { it.mealId }).associateBy { it.id }
        val (liveJobs, deletedJobs) = jobs.partition { job ->
            val meal = mealsById[job.mealId]
            meal != null && meal.deletedAt == null
        }

        if (deletedJobs.isNotEmpty()) {
            logger.info("지워진 식사의 분석 작업을 건너뜁니다: mealIds={}", deletedJobs.map { it.mealId })
            mealAnalysisJobRepository.deleteAllByIdInBatch(deletedJobs.map { it.id })
        }
        if (liveJobs.isEmpty()) {
            return emptyList()
        }

        mealAnalysisJobRepository.updateLease(liveJobs.map { it.id }, workerProperties.leaseTimeToLive.toSeconds())
        mealRepository.updateMealStatuses(liveJobs.map { it.mealId }, MealStatus.ANALYZING, Instant.now(clock))

        return liveJobs.map {
            MealAnalysisJobResult(
                jobId = it.id,
                mealId = it.mealId,
                leaseCount = it.leaseCount + 1,
                imageKey = mealsById.getValue(it.mealId).imageKey,
            )
        }
    }
}
