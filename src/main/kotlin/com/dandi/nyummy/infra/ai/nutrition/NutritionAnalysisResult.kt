package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.meal.dto.Nutrition

data class NutritionAnalysisResult(val name: String, val nutrition: Nutrition, val iconId: Long)
