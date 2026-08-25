package com.dandi.nyummy.meal.service

import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UpdateMealService(private val mealRepository: MealRepository) {

    /**
     * 식사의 분석 상태를 변경하고 즉시 커밋한다.
     *
     * @param meal 상태를 변경할 [Meal]
     * @param status 변경할 [MealStatus]
     */
    @Transactional
    fun updateStatus(meal: Meal, status: MealStatus) {
        meal.updateStatus(status)
        mealRepository.save(meal)
    }

    /**
     * 식사의 영양 분석 결과(이름·아이콘·영양)를 저장하고 즉시 커밋한다.
     *
     * @param meal 분석 결과를 저장할 [Meal]
     * @param analysisResult 저장할 [NutritionAnalysisResult]
     */
    @Transactional
    fun updateAnalysisResult(meal: Meal, analysisResult: NutritionAnalysisResult) {
        meal.updateAnalysisResult(analysisResult)
        mealRepository.save(meal)
    }
}
