package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisClient
import com.dandi.nyummy.meal.dto.GetStatusResponse
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.mapper.toGetStatusResponse
import com.dandi.nyummy.meal.repository.MealRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

@Service
class AnalysisService(
    private val mealRepository: MealRepository,
    private val nutritionAnalysisClient: NutritionAnalysisClient,
) {
    /**
     * 식사의 영양 분석 상태를 조회한다.
     *
     * @param userId 조회를 요청한 사용자 ID (소유권 검증에 사용)
     * @param mealId 조회할 [Meal]의 ID
     * @return 분석 상태를 담은 [GetStatusResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, userId가 소유자가 아닌 경우
     */
    fun getStatus(userId: Long, mealId: Long): GetStatusResponse {
        val meal = mealRepository.findByIdOrNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND, "Meal Not Found")

        meal.validateOwnership(userId)

        return meal.toGetStatusResponse()
    }

    fun analyzeNutrition(userId: Long, mealId: Long) {
        val meal = mealRepository.findByIdOrNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        meal.updateStatus(MealStatus.ANALYZING)

        runCatching {
            val nutrition = nutritionAnalysisClient.analyzeNutrition(meal.imageKey)
            meal.updateNutrition(nutrition)
            meal.updateStatus(MealStatus.COMPLETED)
        }.onFailure {
            meal.updateStatus(MealStatus.FAILED)
        }
    }

    fun retryNutritionAnalysis(userId: Long, mealId: Long): GetStatusResponse {
        val meal = mealRepository.findByIdOrNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        if (meal.status == MealStatus.FAILED) {
            meal.updateStatus(MealStatus.ANALYZING)
            analyzeNutrition(userId, meal.id)
        }

        return meal.toGetStatusResponse()
    }
}
