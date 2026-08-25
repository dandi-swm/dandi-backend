package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisClient
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.mapper.toMealStatusResponse
import com.dandi.nyummy.meal.repository.MealRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AnalysisService(
    private val updateMealService: UpdateMealService,
    private val mealRepository: MealRepository,
    private val nutritionAnalysisClient: NutritionAnalysisClient,
) {
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
        val meal = mealRepository.findByIdOrNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND, "Meal Not Found")

        meal.validateOwnership(userId)

        return meal.toMealStatusResponse()
    }

    /**
     * 식사 이미지를 분석해 이름·아이콘·영양을 저장하고,
     * 분석 상태 전이(WAITING/FAILED → ANALYZING → COMPLETED/FAILED)를 전담한다.
     *
     * 상태 변경은 [UpdateMealService]를 통해 각각 독립 트랜잭션으로 즉시 커밋되므로,
     * 분석이 진행되는 동안에도 [getStatus] 폴링이 ANALYZING 상태를 조회할 수 있다.
     * 분석 실패는 예외로 전파하지 않고 FAILED 상태로 기록한다.
     * 소유권 검증은 호출자가 보장한다.
     *
     * @param meal 분석할 [Meal]
     *  (상태가 [MealStatus.ANALYZABLE_STATUSES]에 속하지 않으면 아무 작업도 하지 않는다)
     */
    fun analyzeNutrition(meal: Meal) {
        if (meal.status !in MealStatus.ANALYZABLE_STATUSES) {
            return
        }

        updateMealService.updateStatus(meal, MealStatus.ANALYZING)

        var status = MealStatus.COMPLETED
        try {
            val analysisResult = nutritionAnalysisClient.analyzeNutrition(meal.imageKey)
            updateMealService.updateAnalysisResult(meal, analysisResult)
        } catch (e: RuntimeException) {
            status = MealStatus.FAILED
        }

        updateMealService.updateStatus(meal, status)
    }

    /**
     * FAILED 상태의 식사에 대해 영양 분석을 재시도한다.
     *
     * @param userId 재시도를 요청한 사용자 ID (소유권 검증에 사용)
     * @param mealId 재시도할 [Meal]의 ID
     * @return 재시도 결과의 분석 상태를 담은 [MealStatusResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, userId가 소유자가 아닌 경우
     * @throws BusinessException [MealErrorCode.ANALYSIS_NOT_RETRYABLE] 식사가 FAILED 상태가 아닌 경우
     */
    fun retryNutritionAnalysis(userId: Long, mealId: Long): MealStatusResponse {
        val meal = mealRepository.findByIdOrNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        if (meal.status != MealStatus.FAILED) {
            throw BusinessException(MealErrorCode.ANALYSIS_NOT_RETRYABLE)
        }

        analyzeNutrition(meal)

        return meal.toMealStatusResponse()
    }
}
