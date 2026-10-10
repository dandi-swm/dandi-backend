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
    val iconId: Long,
)

data class DailyNutritionResponse(val current: Nutrition, val target: Nutrition)

data class MonthlyMealsResponse(val year: Int, val month: Int, val days: List<MonthlyMealDayResponse>)

data class MonthlyMealDayResponse(

    val date: LocalDate,
    val isCurrentMonth: Boolean,
    val dailyNutritionEvaluation: DailyNutritionEvaluation,
    val foodIconIds: List<Long>,
)

data class Streak(val streakDays: Int, val recordsUntilNextReward: Int)

/**
 * 오늘의 식사 현황.
 *
 * [todayRecordedCount]와 [todayAttemptCount]는 세는 대상이 다르다. 전자는 삭제하지 않고 남아
 * 있는 식사 수(화면 목록 개수)고, 후자는 삭제·분석 실패까지 포함한 기록 시도 횟수다.
 * 제한([todayMaxAttemptCount])과 비교할 값은 후자이므로, 5번 올리고 2개를 지운 사용자는
 * `recorded=3`, `attempt=5`가 되어 더 등록할 수 없다.
 */
data class TodayMealSummary(
    val todayRecordedCount: Int,
    val todayAttemptCount: Int,
    val todayMaxAttemptCount: Int,
    val todayCurrentCalory: Int,
    val todayTargetCalory: Int,
)
