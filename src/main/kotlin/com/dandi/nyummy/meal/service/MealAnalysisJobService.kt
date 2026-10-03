package com.dandi.nyummy.meal.service

import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisResult
import com.dandi.nyummy.meal.enum.MealAnalysisQueueStatus
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.queue.ClaimedMealAnalysis
import com.dandi.nyummy.meal.repository.MealAnalysisQueueRepository
import com.dandi.nyummy.meal.repository.MealRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class MealAnalysisJobService(
    private val queueRepository: MealAnalysisQueueRepository,
    private val mealRepository: MealRepository,
    private val clock: Clock,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun claim(limit: Int): List<ClaimedMealAnalysis> {
        require(limit > 0)
        val now = Instant.now(clock)
        return queueRepository.findReadyForUpdate(limit).mapNotNull { job ->
            job.start(now)
            val meal = mealRepository.findByIdForUpdate(job.mealId)
            if (meal == null || meal.deletedAt != null || meal.status != MealStatus.WAITING) {
                job.fail(now)
                return@mapNotNull null
            }
            meal.updateStatus(MealStatus.ANALYZING)
            ClaimedMealAnalysis(job.id, meal.id, meal.imageKey, meal.name)
        }
    }

    @Transactional
    fun complete(job: ClaimedMealAnalysis, result: NutritionAnalysisResult) {
        val queued = queueRepository.findByIdForUpdate(job.queueId) ?: return
        if (queued.status != MealAnalysisQueueStatus.PROCESSING) return
        check(queued.mealId == job.mealId)

        val now = Instant.now(clock)
        val meal = mealRepository.findByIdForUpdate(queued.mealId)
        if (meal == null || meal.deletedAt != null || meal.status != MealStatus.ANALYZING) {
            queued.fail(now)
            return
        }

        // 분석 중 사용자가 변경한 이름은 보존한다.
        val editedName = meal.name.takeIf { it != job.mealName }
        meal.updateAnalysisResult(result)
        if (editedName != null) meal.updateName(editedName)
        meal.updateStatus(MealStatus.COMPLETED)
        queued.complete(now)
    }

    @Transactional
    fun fail(queueId: Long) {
        val job = queueRepository.findByIdForUpdate(queueId) ?: return
        if (job.status != MealAnalysisQueueStatus.PROCESSING) return

        val meal = mealRepository.findByIdForUpdate(job.mealId)
        if (meal != null && meal.deletedAt == null && meal.status == MealStatus.ANALYZING) {
            meal.updateStatus(MealStatus.FAILED)
        }
        job.fail(Instant.now(clock))
    }
}
