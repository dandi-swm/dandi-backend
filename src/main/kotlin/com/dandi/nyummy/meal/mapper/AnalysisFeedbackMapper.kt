package com.dandi.nyummy.meal.mapper

import com.dandi.nyummy.meal.dto.CreateAnalysisFeedbackRequest
import com.dandi.nyummy.meal.entity.AnalysisFeedback

fun CreateAnalysisFeedbackRequest.toAnalysisFeedback(mealId: Long, userId: Long) = AnalysisFeedback(
    rating = rating,
    reason = reason,
    mealId = mealId,
    userId = userId,
)
