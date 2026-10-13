package com.dandi.nyummy.meal.calculator

import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.DailyNutritionEvaluation
import com.dandi.nyummy.meal.mapper.toNutrition
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.enum.Gender
import java.time.LocalDate
import java.time.Period

private val DEFAULT_INTAKE = Nutrition(calory = 2000, carbs = 250, protein = 100, fat = 70)

private val MACRO_NUTRIENTS = listOf(
    Nutrition::carbs,
    Nutrition::protein,
    Nutrition::fat,
)

/**
 * 하루치 식사를 합산해 평가한다. 기록이 없으면 UNRECORDED다.
 *
 * @param intakeTolerance 권장량 대비 허용 오차 (0.2 = 80~120%)
 * @param minPositiveMacroCount 탄단지 3종 중 허용 범위에 들어야 하는 최소 개수
 */
fun calculateDailyNutritionEvaluation(
    meals: List<Meal>,
    recommended: Nutrition,
    intakeTolerance: Double,
    minPositiveMacroCount: Int,
): DailyNutritionEvaluation {
    if (meals.isEmpty()) {
        return DailyNutritionEvaluation.UNRECORDED
    }

    val totalNutrition = meals.fold(Nutrition.ZERO) { acc, meal -> acc + meal.toNutrition() }

    if (isPositiveNutrition(totalNutrition, recommended, intakeTolerance, minPositiveMacroCount)) {
        return DailyNutritionEvaluation.POSITIVE
    }

    return DailyNutritionEvaluation.NEGATIVE
}

/**
 * 칼로리가 허용 범위에 들고, 탄단지 중 [minPositiveMacroCount]개 이상이 같은 범위에 들면 POSITIVE로 본다.
 *
 * 칼로리를 필수로 두는 이유는 목표값 자체가 칼로리에서 나오기 때문이다 —
 * [calculateRecommendedDailyIntake]가 칼로리를 탄 50 / 단 20 / 지 30으로 쪼개 탄단지 목표를 만든다.
 * 그래서 탄단지 3종을 모두 요구하면 "총량이 적정한가"가 아니라 "매크로 비율이 50/20/30에 가까운가"를
 * 묻게 된다. 일반 식단에서 단백질 20%(2000kcal 기준 100g)를 맞추기 어려워 대부분의 날이 NEGATIVE가
 * 되므로, 기본값은 과반(2개)만 요구한다.
 */
private fun isPositiveNutrition(
    totalNutrition: Nutrition,
    recommended: Nutrition,
    intakeTolerance: Double,
    minPositiveMacroCount: Int,
): Boolean = isPositiveNutrient(totalNutrition.calory, recommended.calory, intakeTolerance) &&
    MACRO_NUTRIENTS.count { nutrient ->
        isPositiveNutrient(nutrient(totalNutrition), nutrient(recommended), intakeTolerance)
    } >= minPositiveMacroCount

/** 권장량의 `1 ± intakeTolerance` 범위에 드는지. 양 끝을 모두 포함한다. */
private fun isPositiveNutrient(totalValue: Int, recommendedValue: Int, intakeTolerance: Double): Boolean =
    totalValue >= recommendedValue * (1 - intakeTolerance) &&
        totalValue <= recommendedValue * (1 + intakeTolerance)

fun calculateRecommendedDailyIntake(profile: Profile?, today: LocalDate): Nutrition {
    val birth = profile?.birth ?: return DEFAULT_INTAKE
    val height = profile.height ?: return DEFAULT_INTAKE
    val weight = profile.weight ?: return DEFAULT_INTAKE
    val genderConstant = when (profile.gender) {
        Gender.MALE -> 5
        Gender.FEMALE -> -161
        Gender.OTHER, null -> return DEFAULT_INTAKE
    }

    /**
     *  Mifflin-St Jeor 공식 (기초 대사량)
     *  : 실제 권장 칼로리는 기초 대사량 * 1.375 (활동량 계수)
     *  남성 : BMR = 10 * 몸무게 + 6.25 * 키 - 5 * 나이 + 5
     *  여성 : BMR = 10 * 몸무게 + 6.25 * 키 - 5 * 나이 - 161
     */

    val age = calculateAge(birth, today)
    val bmr = (10 * weight + 6.25 * height - 5 * age + genderConstant)
    val calory = bmr * 1.375

    return Nutrition(
        calory = calory.toInt(),
        carbs = (calory * 0.5 / 4).toInt(),
        protein = (calory * 0.2 / 4).toInt(),
        fat = (calory * 0.3 / 9).toInt(),
    )
}

private fun calculateAge(birth: LocalDate, today: LocalDate): Int = Period.between(birth, today).years
