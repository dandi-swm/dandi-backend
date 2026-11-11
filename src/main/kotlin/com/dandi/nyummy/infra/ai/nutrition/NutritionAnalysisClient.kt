package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.NutritionAnalysisResult

interface NutritionAnalysisClient {

    /**
     * @param language 결과의 음식 이름·고양이 코멘트를 쓸 언어. 식사를 기록한 요청의 앱 언어다
     */
    fun analyzeNutrition(imageKey: String, language: Language): NutritionAnalysisResult
}
