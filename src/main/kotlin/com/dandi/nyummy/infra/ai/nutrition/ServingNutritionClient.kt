package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.enum.MealInputCategory

interface ServingNutritionClient {

    /**
     * 음식 이름마다 1인분 영양과 아이콘을 구한다.
     *
     * @param names 음식 이름들
     * @param inputCategory 식사의 입력 유형. 제품·영양성분표면 이름을 제품 이름으로 보고 그 제품의 영양 정보를 구한다
     * @return [names]와 같은 순서, 같은 개수의 결과
     * @throws AnalysisFailureException 실패를 [AnalysisFailureCategory]로 나눠 던진다
     */
    fun getServingNutritions(names: List<String>, inputCategory: MealInputCategory): List<ServingNutritionResult>
}
