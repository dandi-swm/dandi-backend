package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.mapper.toMealStatusResponse
import com.dandi.nyummy.meal.repository.MealRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AnalysisService(private val mealRepository: MealRepository, private val mealCommandService: MealCommandService) {
    /**
     * 식사의 영양 분석 상태를 조회한다.
     *
     * @param userId 조회를 요청한 사용자 ID (소유권 검증에 사용)
     * @param mealId 조회할 [Meal]의 ID
     * @return 분석 상태를 담은 [MealStatusResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, userId가 소유자가 아닌 경우
     */
    @Transactional(readOnly = true)
    fun getStatus(userId: Long, mealId: Long): MealStatusResponse {
        val meal = mealRepository.getMealByIdAndDeletedAtIsNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND, "Meal Not Found")

        meal.validateOwnership(userId)

        return meal.toMealStatusResponse()
    }

    /**
     * FAILED 상태의 식사에 새 비동기 영양 분석을 요청한다.
     *
     * @param userId 재시도를 요청한 사용자 ID (소유권 검증에 사용)
     * @param mealId 재시도할 [Meal]의 ID
     * @return 재시도 접수 상태(WAITING)를 담은 [MealStatusResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, userId가 소유자가 아닌 경우
     * @throws BusinessException [MealErrorCode.ANALYSIS_NOT_RETRYABLE] 식사가 FAILED 상태가 아닌 경우
     */
    fun retryNutritionAnalysis(userId: Long, mealId: Long): MealStatusResponse =
        mealCommandService.retryAnalysis(userId, mealId)
}
