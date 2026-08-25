package com.dandi.nyummy.infra.ai.nutrition

interface NutritionAnalysisClient {

    fun analyzeNutrition(imageKey: String): NutritionAnalysisResult
}
