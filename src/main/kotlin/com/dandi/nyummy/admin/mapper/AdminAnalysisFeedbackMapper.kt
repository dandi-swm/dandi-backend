package com.dandi.nyummy.admin.mapper

import com.dandi.nyummy.admin.dto.AdminAnalysisFeedbackMealResponse
import com.dandi.nyummy.admin.dto.AdminAnalysisFeedbackResponse
import com.dandi.nyummy.meal.entity.AnalysisFeedback
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.mapper.toNutrition

fun AnalysisFeedback.toAdminAnalysisFeedbackResponse(meal: AdminAnalysisFeedbackMealResponse) =
    AdminAnalysisFeedbackResponse(
        analysisFeedbackId = this.id,
        userId = this.userId,
        rating = this.rating,
        reason = this.reason,
        createdAt = this.createdAt,
        meal = meal,
    )

fun Meal.toAdminAnalysisFeedbackMealResponse(imageUrl: String) = AdminAnalysisFeedbackMealResponse(
    mealId = this.id,
    name = this.name,
    mealAt = this.mealAt,
    nutrition = this.toNutrition(),
    catComment = this.catComment,
    imageUrl = imageUrl,
    deletedAt = this.deletedAt,
)
