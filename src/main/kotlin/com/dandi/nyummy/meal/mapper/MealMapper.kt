package com.dandi.nyummy.meal.mapper

import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.DailyMealResponse
import com.dandi.nyummy.meal.dto.MealResponse
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import java.time.Instant

// DTO -> Entity 변환 확장 함수
fun CreateMealRequest.toEntity(userId: Long, mealAt: Instant, imageKey: String) = Meal(
    status = MealStatus.WAITING,
    imageKey = imageKey,
    mealAt = mealAt,
    userId = userId,
)

fun Meal.toNutrition() = Nutrition(
    calory = this.calory ?: 0,
    carbs = this.carbs ?: 0,
    protein = this.protein ?: 0,
    fat = this.fat ?: 0,
)

fun Meal.toMealStatusResponse() = MealStatusResponse(
    id = this.id,
    status = this.status.name,
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
    // 영양 값과 달리 iconId는 nullable이 아니라 [Meal]의 기본값이 들어 있다. 분석 전이거나
    // 실패한 식사도 그 값이 내려가므로, 아이콘을 보여줄지는 status로 판단해야 한다.
    iconId = this.iconId,
)

fun Meal.toMealResponse(imageUrl: String) = MealResponse(
    mealId = this.id,
    name = this.name,
    mealAt = this.mealAt,
    status = this.status,
    nutrition = this.toNutrition(),
    imageUrl = imageUrl,
    catComment = this.catComment,
    iconId = this.iconId,
)
