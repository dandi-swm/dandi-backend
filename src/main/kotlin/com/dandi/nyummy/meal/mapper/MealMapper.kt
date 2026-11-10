package com.dandi.nyummy.meal.mapper

import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.CreateMealResponse
import com.dandi.nyummy.meal.dto.DailyMealResponse
import com.dandi.nyummy.meal.dto.MealResponse
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.UpdateMealNutritionRequest
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

/** 요청에 담긴 항목만 바꾸고, 생략한 항목은 [meal]의 지금 값을 그대로 둔 [Nutrition]을 만든다. */
fun UpdateMealNutritionRequest.toNutrition(meal: Meal): Nutrition {
    val current = meal.toNutrition()

    return Nutrition(
        calory = this.calory ?: current.calory,
        carbs = this.carbs ?: current.carbs,
        protein = this.protein ?: current.protein,
        fat = this.fat ?: current.fat,
    )
}

fun Meal.toMealStatusResponse() = MealStatusResponse(
    id = this.id,
    status = this.status,
)

fun Meal.toCreateMealResponse(rewardBoxCategory: RewardBoxCategory) = CreateMealResponse(
    id = this.id,
    status = this.status,
    rewardBoxCategory = rewardBoxCategory,
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
    // 영양 값은 0으로 채우지만 iconId는 0이 가리킬 아이콘이 없으므로 null 그대로 내려준다.
    iconId = this.iconId,
)

fun Meal.toMealResponse(imageUrl: String, canCreateAnalysisFeedback: Boolean) = MealResponse(
    mealId = this.id,
    name = this.name,
    mealAt = this.mealAt,
    status = this.status,
    nutrition = this.toNutrition(),
    imageUrl = imageUrl,
    catComment = this.catComment,
    iconId = this.iconId,
    canCreateAnalysisFeedback = canCreateAnalysisFeedback,
)
