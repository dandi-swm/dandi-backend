package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.meal.dto.NutritionAnalysisResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.worker.config.ConditionalOnAnalysisWorkerEnabled
import com.dandi.nyummy.worker.config.WorkerJobProperties
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.AnalysisPushResult
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Worker가 쓰는 짧은 트랜잭션을 모은다. Gemini 호출처럼 오래 걸리는 일은 이 트랜잭션들 밖에서 한다.
 *
 * 잠그는 순서는 작업(meal_analysis_jobs) → 식사(meals)로 고정한다. 반대 순서로 잠그는 트랜잭션이 있으면 서로를 기다려 데드락이 난다.
 *
 * 결과를 쓰는 메서드는 모두 작업이 아직 그 번호(leaseCount)일 때만 쓴다(fencing). lease가 만료돼 다른 Worker가 같은 작업을 가져갔으면
 * 늦게 끝난 Worker는 아무것도 쓰지 않고 끝난다.
 */
@Service
@ConditionalOnAnalysisWorkerEnabled
class MealAnalysisJobTransactionService(
    private val mealAnalysisJobRepository: MealAnalysisJobRepository,
    private val mealRepository: MealRepository,
    private val catRepository: CatRepository,
    private val catService: CatService,
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
     * 가져가지 않고 끝내는 작업도 있다.
     * - 그사이 사용자가 지운 식사의 작업은 지운다. 분석하지 않으므로 Gemini 비용과 알림이 생기지 않는다.
     * - 이미 [WorkerJobProperties.maxLeaseCount]번 가져갔는데 또 lease가 만료된 작업은 지우고 식사를 FAILED로 둔다.
     *   처리하던 Worker가 거듭 죽은 경우라, 다시 가져가도 같은 일이 반복될 수 있다. 알림은 보내지 않는다.
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
        val (leasableJobs, exhaustedJobs) = liveJobs.partition {
            it.leaseCount < workerProperties.analysis.maxLeaseCount
        }

        if (deletedJobs.isNotEmpty()) {
            logger.info("지워진 식사의 분석 작업을 건너뜁니다: mealIds={}", deletedJobs.map { it.mealId })
            mealAnalysisJobRepository.deleteAllByIdInBatch(deletedJobs.map { it.id })
        }
        if (exhaustedJobs.isNotEmpty()) {
            logger.warn("최대 시도 횟수를 채운 분석 작업을 FAILED로 끝냅니다: mealIds={}", exhaustedJobs.map { it.mealId })
            mealAnalysisJobRepository.deleteAllByIdInBatch(exhaustedJobs.map { it.id })
            mealRepository.updateMealStatuses(exhaustedJobs.map { it.mealId }, MealStatus.FAILED, Instant.now(clock))
        }
        if (leasableJobs.isEmpty()) {
            return emptyList()
        }

        mealAnalysisJobRepository.updateLease(
            leasableJobs.map {
                it.id
            },
            workerProperties.analysis.leaseTimeToLive.toSeconds(),
        )
        mealRepository.updateMealStatuses(leasableJobs.map { it.mealId }, MealStatus.ANALYZING, Instant.now(clock))

        return leasableJobs.map {
            val leaseCount = it.leaseCount + 1
            MealAnalysisJobResult(
                jobId = it.id,
                mealId = it.mealId,
                leaseCount = leaseCount,
                imageKey = mealsById.getValue(it.mealId).imageKey,
                canRetry = leaseCount < workerProperties.analysis.maxLeaseCount,
                createdAt = it.createdAt,
            )
        }
    }

    /**
     * 분석 결과를 저장하고 COMPLETED로 확정한다. 작업은 지운다.
     *
     * 고양이의 마지막 식사 시각도 같이 갱신한다. 리텐션 배치가 이 값으로 대상을 고른다. 가입 때 만들어지는 고양이가 없으면
     * 갱신만 건너뛴다. [CatService.updateLastMealAt]이 던지는 예외는 이 트랜잭션을 롤백 전용으로 만들어 결과까지 잃게 하므로
     * 먼저 있는지 확인한다.
     *
     * @return 알림에 쓸 값. 이 Worker의 작업이 아니면(lease가 만료돼 다른 Worker가 가져갔으면) null
     */
    @Transactional
    fun confirmMealAnalysis(job: MealAnalysisJobResult, analysisResult: NutritionAnalysisResult): AnalysisPushResult? {
        val meal = deleteJobAndGetMeal(job) ?: return null

        meal.updateAnalysisResult(analysisResult)
        meal.updateStatus(MealStatus.COMPLETED)

        if (catRepository.existsByUserId(meal.userId)) {
            catService.updateLastMealAt(meal.userId, meal.mealAt)
        } else {
            logger.error("가입 시 생성되어야 할 고양이가 없어 마지막 식사 시각을 갱신하지 않습니다: userId={}", meal.userId)
        }

        return convertAnalysisPushResult(meal)
    }

    /**
     * 작업을 대기로 되돌리고 식사를 WAITING으로 둔다. 다음 polling에서 다시 가져간다.
     *
     * @return 이 Worker의 작업이라 되돌렸으면 true
     */
    @Transactional
    fun retryMealAnalysis(job: MealAnalysisJobResult): Boolean {
        if (mealAnalysisJobRepository.resetLease(job.jobId, job.leaseCount) == 0) {
            logger.info("다른 Worker가 가져간 분석 작업이라 되돌리지 않습니다: jobId={}, leaseCount={}", job.jobId, job.leaseCount)
            return false
        }

        mealRepository.updateMealStatuses(listOf(job.mealId), MealStatus.WAITING, Instant.now(clock))
        return true
    }

    /**
     * 분석을 시작하지 못한 작업을 가져간 적 없는 것처럼 되돌린다. 번호를 하나 내려 시도 횟수를 쓰지 않으며,
     * 식사를 WAITING으로 둔다. [delay]가 지난 뒤의 polling에서 같은 번호로 다시 가져간다.
     *
     * 실행기가 가득 차 작업을 받지 못했을 때 쓴다. 설정 오류로 분석하지 못했을 때도 쓰는데, 이때는 시작한 작업이라
     * lease가 만료된 이전 Worker가 아직 돌고 있으면 내린 번호가 그 Worker의 번호와 같아져 결과를 쓸 수 있다(감수한 위험).
     *
     * @return 이 Worker의 작업이라 되돌렸으면 true
     */
    @Transactional
    fun cancelMealAnalysis(job: MealAnalysisJobResult, delay: Duration): Boolean {
        if (mealAnalysisJobRepository.cancelLease(job.jobId, job.leaseCount, delay.toSeconds()) == 0) {
            logger.info("다른 Worker가 가져간 분석 작업이라 되돌리지 않습니다: jobId={}, leaseCount={}", job.jobId, job.leaseCount)
            return false
        }

        mealRepository.updateMealStatuses(listOf(job.mealId), MealStatus.WAITING, Instant.now(clock))
        return true
    }

    /**
     * 분석을 FAILED로 끝낸다. 작업은 지운다.
     *
     * @return 알림에 쓸 값. 이 Worker의 작업이 아니면 null
     */
    @Transactional
    fun failMealAnalysis(job: MealAnalysisJobResult): AnalysisPushResult? {
        val meal = deleteJobAndGetMeal(job) ?: return null

        meal.updateStatus(MealStatus.FAILED)

        return convertAnalysisPushResult(meal)
    }

    /**
     * 작업이 아직 이 Worker의 것이면 지우고, 식사를 잠가 돌려준다. 작업 → 식사 순서로 잠근다.
     * 지운 행이 없으면 다른 Worker가 가져갔거나 이미 끝난 작업이라 null이다.
     */
    private fun deleteJobAndGetMeal(job: MealAnalysisJobResult): Meal? {
        if (mealAnalysisJobRepository.deleteByIdAndLeaseCount(job.jobId, job.leaseCount) == 0) {
            logger.info("다른 Worker가 가져간 분석 작업이라 결과를 쓰지 않습니다: jobId={}, leaseCount={}", job.jobId, job.leaseCount)
            return null
        }

        return mealRepository.findMealWithLockById(job.mealId)
            ?: run {
                logger.error("분석 작업의 식사가 없습니다: jobId={}, mealId={}", job.jobId, job.mealId)
                null
            }
    }

    private fun convertAnalysisPushResult(meal: Meal) = AnalysisPushResult(
        mealId = meal.id,
        userId = meal.userId,
        mealName = meal.name,
        status = meal.status,
        isDeleted = meal.deletedAt != null,
    )
}
