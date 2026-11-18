package com.dandi.nyummy.meal.calculator

import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.NutritionHint
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MacroNutrient
import java.math.BigDecimal
import java.math.RoundingMode

// 1인분 컬럼(DECIMAL(7,2))의 소수 자릿수
private const val SERVING_SCALE = 2

// 적힌 값에 맞출 때 곱하는 비율의 소수 자릿수. 맞춘 값을 다시 더했을 때 적힌 값에서 벗어나지 않을 만큼 둔다.
private const val RATE_SCALE = 8

// 영양 값의 차이가 이 값 이하이면 비율과 상관없이 같다고 본다. 작은 값은 조금만 달라도 비율로는 크게 벗어난다.
private val SMALL_NUTRIENT_DIFFERENCE = BigDecimal.ONE

/**
 * 음식 한 줄의 영양. 1인분 × 인분을 정수로 반올림한 값이라 응답에 그대로 쓴다.
 *
 * @return 1인분 값이나 인분을 아직 구하지 않은 줄이면 null
 */
fun calculateMealFoodNutrition(mealFood: MealFood): Nutrition? {
    val servingNutrition = mealFood.servingNutrition ?: return null
    val portion = mealFood.portion ?: return null

    return calculateTotalNutrition(listOf(servingNutrition to portion))
}

/**
 * 1인분과 인분으로 구한 음식 한 줄의 영양. 아직 줄에 저장하지 않은 1인분 값으로 줄 영양을 미리 볼 때 쓴다.
 */
fun calculateMealFoodNutrition(servingNutrition: ServingNutrition, portion: BigDecimal): Nutrition =
    calculateTotalNutrition(listOf(servingNutrition to portion))

/**
 * 식사 합계. 줄마다 반올림하지 않고 1인분 × 인분을 소수로 더한 뒤 한 번만 반올림한다.
 * 그래서 [calculateMealFoodNutrition]으로 구한 줄 영양을 더한 값과 1 정도 다를 수 있다.
 *
 * @throws IllegalStateException 1인분 값이나 인분을 아직 구하지 않은 줄이 있는 경우. 합계는 모든 줄의 값을 구한 뒤에 계산한다
 */
fun calculateMealNutrition(mealFoods: List<MealFood>): Nutrition = calculateTotalNutrition(
    mealFoods.map { mealFood ->
        val servingNutrition = checkNotNull(mealFood.servingNutrition) {
            "1인분 값이 없는 음식 줄이 있습니다: mealFoodId=${mealFood.id}"
        }
        val portion = checkNotNull(mealFood.portion) {
            "인분이 없는 음식 줄이 있습니다: mealFoodId=${mealFood.id}"
        }
        servingNutrition to portion
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

/**
 * 사진에 일부만 적혀 있던 값과 이름으로 구한 1인분으로, 그 줄의 1인분을 구한다. 적혀 있던 값은 그대로 둔다.
 *
 * 칼로리가 적혀 있으면 구한 1인분의 탄수화물·단백질·지방을 "적힌 칼로리 ÷ 구한 칼로리" 비율로 맞춘다.
 * 같은 음식이라도 적힌 칼로리만큼의 양을 먹은 것으로 보는 것이다(적힌 520kcal, 구한 650kcal이면 0.8배).
 * 칼로리가 적혀 있지 않거나 구한 칼로리가 0이면 빈 항목을 구한 값 그대로 채운다.
 *
 * @param nutritionHint 사진에 적혀 있던 값. 일부 항목만 있다
 * @param servingNutrition 이름으로 구한 그 음식의 1인분
 */
fun calculateHintedServingNutrition(
    nutritionHint: NutritionHint,
    servingNutrition: ServingNutrition,
): ServingNutrition {
    val hintCalory = nutritionHint.calory
    val rate = if (hintCalory != null && servingNutrition.calory.signum() > 0) {
        hintCalory.divide(servingNutrition.calory, RATE_SCALE, RoundingMode.HALF_UP)
    } else {
        BigDecimal.ONE
    }

    fun calculateNutrient(hint: BigDecimal?, serving: BigDecimal): BigDecimal =
        hint ?: (serving * rate).setScale(SERVING_SCALE, RoundingMode.HALF_UP)

    return ServingNutrition(
        calory = calculateNutrient(nutritionHint.calory, servingNutrition.calory),
        carbs = calculateNutrient(nutritionHint.carbs, servingNutrition.carbs),
        protein = calculateNutrient(nutritionHint.protein, servingNutrition.protein),
        fat = calculateNutrient(nutritionHint.fat, servingNutrition.fat),
    )
}

/**
 * 사진에 적혀 있던 값이 다른 값과 같은지. 적혀 있던 항목만 비교하고, 숫자가 같아야 같다고 본다(소수 자릿수 표현은 가리지 않는다).
 * 저장해 둔 표시값과 새로 읽은 표시값을 비교할 때 쓴다. 둘 다 표시값이라 조금이라도 다르면 다른 값이다.
 *
 * @param nutritionHint 사진에 적혀 있던 값. 일부 항목만 있을 수 있다
 * @param servingNutrition 비교할 값
 */
fun matchesNutritionHint(nutritionHint: NutritionHint, servingNutrition: ServingNutrition): Boolean =
    createNutritionHintPairs(nutritionHint, servingNutrition).all { (hint, serving) -> hint.compareTo(serving) == 0 }

/**
 * 사진에 적혀 있던 값이 다른 값과 허용 오차 안에서 같은지. 적혀 있던 항목만 비교하고, 모든 항목이 맞아야 한다.
 * 모델이 구한 값을 표시값과 비교할 때 쓴다.
 *
 * 항목마다 차이가 적힌 값의 [tolerance] 비율 이하이면 같다고 본다. 작은 값은 비율로 보면 조금만 달라도 크게 벗어나므로
 * (3g과 4g은 33%), 차이가 1 이하이면 비율과 상관없이 같다고 본다.
 *
 * @param tolerance 적힌 값에서 벗어나도 되는 비율(0.1 = ±10%)
 */
fun matchesNutritionHint(nutritionHint: NutritionHint, servingNutrition: ServingNutrition, tolerance: Double): Boolean =
    createNutritionHintPairs(nutritionHint, servingNutrition).all { (hint, serving) ->
        val difference = (hint - serving).abs()

        difference <= SMALL_NUTRIENT_DIFFERENCE || difference <= hint * BigDecimal.valueOf(tolerance)
    }

/** 사진에 적혀 있던 항목과 그 항목의 비교할 값을 짝지은 목록. 적혀 있지 않던 항목은 뺀다. */
private fun createNutritionHintPairs(
    nutritionHint: NutritionHint,
    servingNutrition: ServingNutrition,
): List<Pair<BigDecimal, BigDecimal>> = listOfNotNull(
    nutritionHint.calory?.let { it to servingNutrition.calory },
    nutritionHint.carbs?.let { it to servingNutrition.carbs },
    nutritionHint.protein?.let { it to servingNutrition.protein },
    nutritionHint.fat?.let { it to servingNutrition.fat },
)

/**
 * 사진에 적혀 있던 식사 전체의 값에 음식 줄들의 1인분을 맞춘다. 영양소마다 줄들의 (1인분 × 인분) 합이 적힌 값과 같아지게
 * 1인분을 같은 비율로 늘리거나 줄인다. 음식별 비율은 구한 값 그대로다.
 *
 * 영양소마다 따로 맞춘다. 아래 경우에는 그 영양소를 건드리지 않는다.
 * - 전체 값에 그 영양소가 없거나 0이다. 0은 적혀 있지 않은 것과 구분할 수 없고, 0에 맞추면 그 영양소가 통째로 사라진다.
 * - 줄들의 합이 0이다. 나눌 비율이 없다.
 *
 * 인분은 바꾸지 않는다. 1인분은 소수 둘째 자리에서 반올림하므로 합이 적힌 값과 소수점 아래에서 조금 다를 수 있다.
 *
 * @param servings 줄마다의 (1인분, 인분)
 * @param totalNutritionHint 사진에 적혀 있던 식사 전체의 값
 * @return [servings]와 같은 순서의 맞춘 1인분
 */
fun calculateTotalAdjustedServingNutritions(
    servings: List<Pair<ServingNutrition, BigDecimal>>,
    totalNutritionHint: NutritionHint,
): List<ServingNutrition> {
    fun calculateRate(total: BigDecimal?, nutrient: (ServingNutrition) -> BigDecimal): BigDecimal {
        val sum = servings.sumOf { (servingNutrition, portion) -> nutrient(servingNutrition) * portion }
        if (total == null || total.signum() <= 0 || sum.signum() <= 0) {
            return BigDecimal.ONE
        }

        return total.divide(sum, RATE_SCALE, RoundingMode.HALF_UP)
    }

    val caloryRate = calculateRate(totalNutritionHint.calory, ServingNutrition::calory)
    val carbsRate = calculateRate(totalNutritionHint.carbs, ServingNutrition::carbs)
    val proteinRate = calculateRate(totalNutritionHint.protein, ServingNutrition::protein)
    val fatRate = calculateRate(totalNutritionHint.fat, ServingNutrition::fat)

    return servings.map { (servingNutrition, _) ->
        ServingNutrition(
            calory = (servingNutrition.calory * caloryRate).setScale(SERVING_SCALE, RoundingMode.HALF_UP),
            carbs = (servingNutrition.carbs * carbsRate).setScale(SERVING_SCALE, RoundingMode.HALF_UP),
            protein = (servingNutrition.protein * proteinRate).setScale(SERVING_SCALE, RoundingMode.HALF_UP),
            fat = (servingNutrition.fat * fatRate).setScale(SERVING_SCALE, RoundingMode.HALF_UP),
        )
    }
}

/**
 * 1인분의 칼로리가 탄단지로 계산한 칼로리(탄수화물×4 + 단백질×4 + 지방×9)와 허용 오차 안에서 맞는지.
 * 모델이 낸 값의 앞뒤가 맞는지 보는 검사다. 맞지 않는 값은 다른 식사에 다시 쓰지 않는다.
 *
 * @param tolerance 적힌 칼로리 대비 벗어나도 되는 비율(0.1 = ±10%)
 */
fun matchesMacroNutrientCalory(servingNutrition: ServingNutrition, tolerance: Double): Boolean {
    val macroNutrientCalory =
        servingNutrition.carbs * BigDecimal(CALORY_PER_GRAM.getValue(MacroNutrient.CARBS)) +
            servingNutrition.protein * BigDecimal(CALORY_PER_GRAM.getValue(MacroNutrient.PROTEIN)) +
            servingNutrition.fat * BigDecimal(CALORY_PER_GRAM.getValue(MacroNutrient.FAT))
    val difference = (servingNutrition.calory - macroNutrientCalory).abs()

    return difference <= servingNutrition.calory * BigDecimal.valueOf(tolerance)
}

/**
 * (1인분, 인분) 쌍들의 1인분 × 인분을 영양소별로 소수로 더한 뒤 정수로 반올림한다.
 * 음식 줄에 저장하기 전의 값으로 식사 합계를 미리 볼 때는 이 함수를 바로 쓰고, 저장된 줄로는 [calculateMealNutrition]을 쓴다.
 */
fun calculateTotalNutrition(servings: List<Pair<ServingNutrition, BigDecimal>>): Nutrition {
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
