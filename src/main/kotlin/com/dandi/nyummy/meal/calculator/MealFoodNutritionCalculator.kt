package com.dandi.nyummy.meal.calculator

import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.entity.MealFood
import java.math.BigDecimal
import java.math.RoundingMode

// 1인분 컬럼(DECIMAL(7,2))의 소수 자릿수
private const val SERVING_SCALE = 2

/**
 * 음식 한 줄의 영양. 1인분 × 인분을 정수로 반올림한 값이라 응답에 그대로 쓴다.
 *
 * @return 1인분 값을 아직 구하지 않은 줄이면 null
 */
fun calculateMealFoodNutrition(mealFood: MealFood): Nutrition? {
    val servingNutrition = mealFood.servingNutrition ?: return null

    return calculateTotalNutrition(listOf(servingNutrition to mealFood.portion))
}

/**
 * 식사 합계. 줄마다 반올림하지 않고 1인분 × 인분을 소수로 더한 뒤 한 번만 반올림한다.
 * 그래서 [calculateMealFoodNutrition]으로 구한 줄 영양을 더한 값과 1 정도 다를 수 있다.
 *
 * @throws IllegalStateException 1인분 값을 아직 구하지 않은 줄이 있는 경우. 합계는 모든 줄의 1인분 값을 구한 뒤에 계산한다
 */
fun calculateMealNutrition(mealFoods: List<MealFood>): Nutrition = calculateTotalNutrition(
    mealFoods.map { mealFood ->
        val servingNutrition = checkNotNull(mealFood.servingNutrition) {
            "1인분 값이 없는 음식 줄이 있습니다: mealFoodId=${mealFood.id}"
        }
        servingNutrition to mealFood.portion
    },
)

/**
 * 사용자가 직접 고친 줄 영양에서 1인분을 구한다. 1인분 = 고친 값 ÷ 지금 인분이고, 소수 둘째 자리까지 둔다.
 * 그 자릿수면 같은 인분을 다시 곱해 반올림했을 때 고친 값이 그대로 나온다(예: 1.5인분에 415 → 276.67 → 415).
 *
 * @param nutrition 사용자가 고친 줄 영양
 * @param portion 그 줄의 지금 인분
 */
fun calculateServingNutrition(nutrition: Nutrition, portion: BigDecimal): ServingNutrition = ServingNutrition(
    calory = BigDecimal(nutrition.calory).divide(portion, SERVING_SCALE, RoundingMode.HALF_UP),
    carbs = BigDecimal(nutrition.carbs).divide(portion, SERVING_SCALE, RoundingMode.HALF_UP),
    protein = BigDecimal(nutrition.protein).divide(portion, SERVING_SCALE, RoundingMode.HALF_UP),
    fat = BigDecimal(nutrition.fat).divide(portion, SERVING_SCALE, RoundingMode.HALF_UP),
)

/** (1인분, 인분) 쌍들의 1인분 × 인분을 영양소별로 소수로 더한 뒤 정수로 반올림한다. */
private fun calculateTotalNutrition(servings: List<Pair<ServingNutrition, BigDecimal>>): Nutrition {
    fun calculateTotal(nutrient: (ServingNutrition) -> BigDecimal): Int = servings
        .sumOf { (servingNutrition, portion) -> nutrient(servingNutrition) * portion }
        .setScale(0, RoundingMode.HALF_UP)
        .intValueExact()

    return Nutrition(
        calory = calculateTotal(ServingNutrition::calory),
        carbs = calculateTotal(ServingNutrition::carbs),
        protein = calculateTotal(ServingNutrition::protein),
        fat = calculateTotal(ServingNutrition::fat),
    )
}
