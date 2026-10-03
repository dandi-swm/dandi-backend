package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealOutbox
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.event.MealAnalysisRequested
import com.dandi.nyummy.meal.mapper.toMealStatusResponse
import com.dandi.nyummy.meal.repository.MealOutboxRepository
import com.dandi.nyummy.meal.repository.MealRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class MealCommandService(
    private val mealRepository: MealRepository,
    private val outboxRepository: MealOutboxRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun createMeal(meal: Meal): MealStatusResponse {
        mealRepository.save(meal)
        requestAnalysis(meal.id)
        // 커밋 후 Worker가 상태를 바꿔도 생성 응답은 접수 당시 상태를 반환한다.
        return meal.toMealStatusResponse()
    }

    @Transactional
    fun retryAnalysis(userId: Long, mealId: Long): MealStatusResponse {
        val meal = mealRepository.findByIdForUpdate(mealId)
            ?.takeIf { it.deletedAt == null }
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)
        meal.validateOwnership(userId)
        if (meal.status != MealStatus.FAILED) {
            throw BusinessException(MealErrorCode.ANALYSIS_NOT_RETRYABLE)
        }

        meal.updateStatus(MealStatus.WAITING)
        requestAnalysis(meal.id)
        return meal.toMealStatusResponse()
    }

    private fun requestAnalysis(mealId: Long) {
        val outbox = outboxRepository.save(MealOutbox(mealId, Instant.now(clock)))
        events.publishEvent(MealAnalysisRequested(outbox.id))
    }
}
