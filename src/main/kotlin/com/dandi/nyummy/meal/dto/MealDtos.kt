package com.dandi.nyummy.meal.dto

import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.meal.enum.DailyNutritionEvaluation
import com.dandi.nyummy.meal.enum.MealStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

data class CreateMealRequest(

    @field:NotBlank
    val imageKey: String,

)

/**
 * 바꾸려는 항목만 담는다. 생략하거나 null로 보낸 항목은 유지되고, 0은 0으로 수정한다.
 */
data class UpdateMealNutritionRequest(
    @field:Min(value = 0, message = "칼로리는 0 이상이어야 합니다.")
    @field:Max(value = 9999, message = "칼로리는 9999 이하여야 합니다.")
    val calory: Int? = null,

    @field:Min(value = 0, message = "탄수화물은 0 이상이어야 합니다.")
    @field:Max(value = 999, message = "탄수화물은 999 이하여야 합니다.")
    val carbs: Int? = null,

    @field:Min(value = 0, message = "단백질은 0 이상이어야 합니다.")
    @field:Max(value = 999, message = "단백질은 999 이하여야 합니다.")
    val protein: Int? = null,

    @field:Min(value = 0, message = "지방은 0 이상이어야 합니다.")
    @field:Max(value = 999, message = "지방은 999 이하여야 합니다.")
    val fat: Int? = null,
)

data class MealResponse(
    val mealId: Long,
    val name: String,
    val mealAt: Instant,
    val status: MealStatus,
    val nutrition: Nutrition,
    val imageUrl: String,
    val canCreateAnalysisFeedback: Boolean,
    val catComment: String? = "",
    val iconId: Long?,
)

data class MealStatusResponse(val id: Long, val status: MealStatus)

data class CreateMealResponse(val id: Long, val status: MealStatus, val rewardBoxCategory: RewardBoxCategory)

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
    val iconId: Long?,
)

data class DailyNutritionResponse(val current: Nutrition, val target: Nutrition)

data class MonthlyMealsResponse(val year: Int, val month: Int, val days: List<MonthlyMealDayResponse>)

data class MonthlyMealDayResponse(

    val date: LocalDate,
    val isCurrentMonth: Boolean,
    val dailyNutritionEvaluation: DailyNutritionEvaluation,
    val foodIconIds: List<Long>,
)

/**
 * 오늘의 식사 현황.
 *
 * [todayRecordedCount]와 [todayAttemptCount]는 세는 대상이 다르다. 전자는 삭제하지 않고 남아
 * 있는 식사 수(화면 목록 개수)고, 후자는 삭제·분석 실패까지 포함한 기록 시도 횟수다.
 * 제한([todayMaxAttemptCount])과 비교할 값은 후자이므로, 5번 올리고 2개를 지운 사용자는
 * `recorded=3`, `attempt=5`가 되어 더 등록할 수 없다.
 */
data class TodayMealSummaryResponse(
    val todayRecordedCount: Int,
    val todayAttemptCount: Int,
    val todayMaxAttemptCount: Int,
    val todayCurrentCalory: Int,
    val todayTargetCalory: Int,
)

data class CreateAnalysisFeedbackRequest(
    // 실제 범위·단위 검증은 AnalysisFeedbackService.validateRating에서 한다. 아래는 Swagger 문서용.
    @field:Schema(
        description = "별점 (0.5 ~ 5.0, 0.5 단위)",
        example = "4.5",
        minimum = "0.5",
        maximum = "5.0",
        multipleOf = 0.5,
    )
    val rating: Float,

    @field:Size(max = 500, message = "평가 이유는 500자 이하여야 합니다.")
    val reason: String? = null,
)
