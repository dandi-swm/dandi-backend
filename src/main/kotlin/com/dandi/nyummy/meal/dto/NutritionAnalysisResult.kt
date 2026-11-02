package com.dandi.nyummy.meal.dto

data class NutritionAnalysisResult(
    val name: String,
    val nutrition: Nutrition,
    val catComment: String,
    val iconId: Long,
)
