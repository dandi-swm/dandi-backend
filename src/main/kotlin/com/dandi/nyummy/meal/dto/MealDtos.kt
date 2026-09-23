package com.dandi.nyummy.meal.dto

import com.dandi.nyummy.meal.enum.DailyNutritionEvaluation
import com.dandi.nyummy.meal.enum.MealStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate

data class CreateMealRequest(

    @field:NotBlank
    val imageKey: String,

)

data class UploadImageRequest(
    @field:NotBlank()
    val contentType: String,

    @field:NotNull()
    val fileSizeBytes: Long,
)

data class UploadImageResponse(
    val uploadUrl: String,
    val imageKey: String,
    val uploadMethod: String,
    val uploadHeaders: Map<String, String>,
    val expiresAt: String,
)

data class MealResponse(

    val mealId: Long,

    val name: String,

    val mealAt: Instant,

    val status: MealStatus,

    val nutrition: Nutrition,

    val imageUrl: String,

    val catComment: String? = "",

    val iconId: Long? = 1,
)

data class MealStatusResponse(val id: Long, val status: String)

data class DailyMealsResponse(

    val date: LocalDate,
    val meals: List<DailyMealResponse>,
    val dailyNutrition: DailyNutritionResponse,
)

data class DailyMealResponse(

    val mealId: Long,
    val name: String,
    val mealAt: Instant,
    val calory: Int,
    val carbs: Int,
    val protein: Int,
    val fat: Int,
    val status: MealStatus,
)

data class DailyNutritionResponse(val current: Nutrition, val target: Nutrition)

data class MonthlyMealsResponse(val year: Int, val month: Int, val days: List<MonthlyMealDayResponse>)

data class MonthlyMealDayResponse(

    val date: LocalDate,
    val isCurrentMonth: Boolean,
    val dailyNutritionEvaluation: DailyNutritionEvaluation,
    val foodIconIds: List<Long>,
)
