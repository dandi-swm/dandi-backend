package com.dandi.nyummy.meal.mapper

import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.DailyMealResponse
import com.dandi.nyummy.meal.dto.GetStatusResponse
import com.dandi.nyummy.meal.dto.MealResponse
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import java.time.Instant

// DTO -> Entity 변환 확장 함수
fun CreateMealRequest.toEntity(userId: Long, imageKey: String) = Meal(
    status = MealStatus.WAITING,
    imageKey = imageKey,
    mealAt = this.mealAt,
    createdAt = Instant.now(),
    userId = userId,
    iconId = 1L,
)

fun Meal.toNutrition() = Nutrition(
    calory = this.calory ?: 0,
    carbs = this.carbs ?: 0,
    protein = this.protein ?: 0,
    fat = this.fat ?: 0,
)

fun Meal.toGetStatusResponse() = GetStatusResponse(
    id = this.id,
    status = this.status.name,
    nutrition = this.toNutrition(),
)

fun Meal.toDailyMealResponse() = DailyMealResponse(
    mealId = this.id,
    name = this.name,
    mealAt = this.mealAt,
    calory = this.calory ?: 0,
    carbs = this.carbs ?: 0,
    protein = this.protein ?: 0,
    fat = this.fat ?: 0,
    status = this.status,
)

fun Meal.toMealResponse(imageUrl: String) = MealResponse(
    mealId = this.id,
    name = this.name,
    mealAt = this.mealAt,
    status = this.status,
    nutrition = this.toNutrition(),
    imageUrl = imageUrl,
    iconId = this.iconId,
)
