package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.meal.dto.NutritionAnalysisResult

interface NutritionAnalysisClient {

    fun analyzeNutrition(imageKey: String): NutritionAnalysisResult
}
