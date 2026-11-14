package com.dandi.nyummy.meal.mapper

import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.meal.calculator.calculateMealFoodNutrition
import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.CreateMealResponse
import com.dandi.nyummy.meal.dto.CreateMealV2Response
import com.dandi.nyummy.meal.dto.DailyMealResponse
import com.dandi.nyummy.meal.dto.MealFoodResponse
import com.dandi.nyummy.meal.dto.MealRecognitionOutcome
import com.dandi.nyummy.meal.dto.MealResponse
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.UpdateMealNutritionRequest
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealAnalysisVersion
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealStatus
import java.time.Instant

// DTO -> Entity 변환 확장 함수
fun CreateMealRequest.toEntity(userId: Long, mealAt: Instant, imageKey: String) = Meal(
    status = MealStatus.WAITING,
    imageKey = imageKey,
    mealAt = mealAt,
    userId = userId,
)

/**
 * v2 식사. 상태·대표 이름·입력 유형을 식사 기록 요청 안에서 음식 인식을 시도한 결과에 맞춘다.
 * - 음식으로 인식했으면 영양 계산을 기다리는 WAITING이고, AI가 추천한 대표 이름과 입력 유형을 채운다.
 * - 음식이 아니면 FAILED다.
 * - 요청 안에서 인식을 끝내지 못했으면 Worker의 인식을 기다리는 RECOGNIZING이고, 이름과 입력 유형은 인식한 뒤에 채운다.
 */
fun CreateMealRequest.toEntity(userId: Long, mealAt: Instant, imageKey: String, outcome: MealRecognitionOutcome) = Meal(
    status = when (outcome) {
        is MealRecognitionOutcome.Recognized -> MealStatus.WAITING
        MealRecognitionOutcome.NotFood -> MealStatus.FAILED
        is MealRecognitionOutcome.Deferred -> MealStatus.RECOGNIZING
    },
    imageKey = imageKey,
    mealAt = mealAt,
    userId = userId,
    analysisVersion = MealAnalysisVersion.V2,
).apply {
    when (outcome) {
        is MealRecognitionOutcome.Recognized -> {
            updateName(outcome.recognition.name)
            updateInputCategory(outcome.recognition.inputCategory)
        }

        MealRecognitionOutcome.NotFood -> updateInputCategory(MealInputCategory.NOT_FOOD)

        is MealRecognitionOutcome.Deferred -> Unit
    }
}

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

/**
 * v2 식사 기록 응답. 대표 이름과 음식 줄은 음식으로 인식한 식사에만 있다.
 * 아직 인식하지 못한 식사(RECOGNIZING)는 이름이 null이고, 음식이 아닌 식사는 빈 문자열이다.
 *
 * @param mealFoods 저장한 음식 줄(순서대로). 음식으로 인식하지 못했으면 빈 목록
 */
fun Meal.toCreateMealV2Response(rewardBoxCategory: RewardBoxCategory, mealFoods: List<MealFood>) = CreateMealV2Response(
    id = this.id,
    status = this.status,
    rewardBoxCategory = rewardBoxCategory,
    name = this.name.takeIf { this.status != MealStatus.RECOGNIZING },
    foods = mealFoods.takeIf { it.isNotEmpty() }?.map { it.toMealFoodResponse() },
)

/** 음식 한 줄. 줄 영양은 1인분 × 인분이고, 1인분 값을 아직 구하지 않은 줄은 null이다. */
fun MealFood.toMealFoodResponse() = MealFoodResponse(
    mealFoodId = this.id,
    name = this.name,
    portion = this.portion,
    nutrition = calculateMealFoodNutrition(this),
    isNutritionFixed = this.isNutritionFixed,
    iconId = this.iconId,
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
