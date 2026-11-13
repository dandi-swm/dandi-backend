package com.dandi.nyummy.meal.dto

import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.meal.enum.DailyNutritionEvaluation
import com.dandi.nyummy.meal.enum.MealAnalysisVersion
import com.dandi.nyummy.meal.enum.MealStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.math.BigDecimal
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

/**
 * v2 식사 기록 응답. 5초 안에 인식하면 대표 이름과 음식 목록을 함께 준다.
 *
 * @property name AI가 추천한 대표 이름(20자 이내). 인식 전이면 null, 음식이 아니면 빈 문자열
 * @property foods 인식된 음식 줄. 인식 전이거나 음식이 아니면 null
 */
data class CreateMealV2Response(
    val id: Long,
    val status: MealStatus,
    val rewardBoxCategory: RewardBoxCategory,
    val name: String?,
    val foods: List<MealFoodResponse>?,
)

/**
 * 식사 안의 음식 한 줄. 기록 응답과 단건 조회가 같은 모양을 쓴다.
 *
 * @property portion 인분(0.5~5.0, 0.5 단위). 1.0이 1인분이다
 * @property nutrition 이 줄의 영양(1인분 영양 × 인분). 계산 전이면 null
 * @property isNutritionFixed 사용자가 영양을 직접 고친 줄. 다시 계산할 때 덮어쓰지 않는다
 */
data class MealFoodResponse(
    val mealFoodId: Long,
    val name: String,
    val portion: BigDecimal,
    val nutrition: Nutrition?,
    val isNutritionFixed: Boolean,
)

/**
 * v2 식사 단건 응답. v1 응답에 분석 버전과 음식 줄을 더했다.
 *
 * @property foods 음식 줄. V1 식사이거나 인식 전이면 null
 */
data class MealV2Response(
    val mealId: Long,
    val analysisVersion: MealAnalysisVersion,
    val name: String,
    val mealAt: Instant,
    val status: MealStatus,
    val nutrition: Nutrition,
    val imageUrl: String,
    val canCreateAnalysisFeedback: Boolean,
    val catComment: String?,
    val iconId: Long?,
    val foods: List<MealFoodResponse>?,
)

data class DailyMealsV2Response(
    val date: LocalDate,
    val meals: List<DailyMealV2Response>,
    val dailyNutrition: DailyNutritionResponse,
)

/**
 * v2 하루 목록의 식사 한 끼. v1 항목에 분석 버전을 더했다.
 */
data class DailyMealV2Response(
    val mealId: Long,
    val analysisVersion: MealAnalysisVersion,
    val name: String,
    val mealAt: Instant,
    val calory: Int,
    val carbs: Int,
    val protein: Int,
    val fat: Int,
    val status: MealStatus,
    val iconId: Long?,
)

/**
 * 음식 목록 보내기 요청. 목록 전체를 보낸다. 요청에 없는 기존 줄은 지운다.
 */
data class UpdateMealFoodsRequest(
    @field:NotEmpty
    @field:Valid
    val foods: List<MealFoodRequest>,
)

/**
 * 보낼 음식 한 줄.
 *
 * @property mealFoodId 있는 줄이면 그 id, 새로 추가한 줄이면 null
 * @property nutrition 이번에 영양을 직접 고친 줄만 채운다. 채우면 그 줄은 고정된다
 */
data class MealFoodRequest(
    val mealFoodId: Long?,

    @field:NotBlank
    val name: String,

    // 0.5 단위인지는 MealService에서 검사한다. 아래는 범위 검증과 Swagger 문서용.
    @field:Schema(description = "인분 (0.5 ~ 5.0, 0.5 단위)", example = "1.0", multipleOf = 0.5)
    @field:DecimalMin("0.5")
    @field:DecimalMax("5.0")
    val portion: BigDecimal,

    val nutrition: Nutrition?,
)

data class UpdateMealNameRequest(
    @field:NotBlank
    @field:Size(max = 20)
    val name: String,
)

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
