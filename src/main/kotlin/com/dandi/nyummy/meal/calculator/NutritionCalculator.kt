package com.dandi.nyummy.meal.calculator

import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.DailyNutritionEvaluation
import com.dandi.nyummy.meal.mapper.toNutrition
import com.dandi.nyummy.profile.entity.Profile
import com.dandi.nyummy.profile.enum.Gender
import java.time.LocalDate
import java.time.Period

private val DEFAULT_INTAKE = Nutrition(calory = 2000, carbs = 250, protein = 100, fat = 70)

private val NUTRIENTS = listOf(
    Nutrition::calory,
    Nutrition::carbs,
    Nutrition::protein,
    Nutrition::fat,
)

fun calculateDailyNutritionEvaluation(meals: List<Meal>, recommended: Nutrition): DailyNutritionEvaluation {
    if (meals.isEmpty()) {
        return DailyNutritionEvaluation.UNRECORDED
    }

    val totalNutrition = meals.fold(Nutrition.ZERO) { acc, meal -> acc + meal.toNutrition() }

    if (isPositiveNutrition(totalNutrition, recommended)) {
        return DailyNutritionEvaluation.POSITIVE
    }

    return DailyNutritionEvaluation.NEGATIVE
}

private fun isPositiveNutrition(totalNutrition: Nutrition, recommended: Nutrition): Boolean =
    NUTRIENTS.all { nutrient -> isPositiveNutrient(nutrient(totalNutrition), nutrient(recommended)) }

private fun isPositiveNutrient(totalValue: Int, recommendedValue: Int): Boolean =
    recommendedValue * 0.9 <= totalValue && totalValue < recommendedValue * 1.5

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
