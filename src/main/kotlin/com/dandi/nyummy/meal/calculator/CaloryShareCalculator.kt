package com.dandi.nyummy.meal.calculator

import com.dandi.nyummy.meal.dto.CaloryShareBalanceResult
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.enum.MacroNutrient

/**
 * 탄단지의 칼로리 구성비가 목표 구성비에서 이 비율(±20%)을 넘게 벗어나야 많거나 부족하다고 본다.
 * 주간 리포트의 강조 영양소와 고양이 코멘트의 많음·부족이 같은 값을 쓴다.
 */
const val CALORY_SHARE_TOLERANCE = 0.2

// 1g당 칼로리
private val CALORY_PER_GRAM = mapOf(MacroNutrient.CARBS to 4, MacroNutrient.PROTEIN to 4, MacroNutrient.FAT to 9)

/**
 * 탄단지 각각이 탄단지 칼로리 합에서 차지하는 비율(0~1). 합이 0이면 모두 0이다.
 */
fun calculateCaloryShares(nutrition: Nutrition): Map<MacroNutrient, Double> {
    val calories = mapOf(
        MacroNutrient.CARBS to nutrition.carbs,
        MacroNutrient.PROTEIN to nutrition.protein,
        MacroNutrient.FAT to nutrition.fat,
    ).mapValues { (nutrient, gram) -> gram * CALORY_PER_GRAM.getValue(nutrient) }
    val total = calories.values.sum()

    return calories.mapValues { (_, calory) -> if (total <= 0) 0.0 else calory.toDouble() / total }
}

/**
 * 목표 구성비 대비 상대 편차. 목표가 20%인 단백질이 10%면 -0.5다. 목표 구성비가 0인 영양소는 0으로 둔다.
 */
fun calculateCaloryShareDeviations(current: Nutrition, target: Nutrition): Map<MacroNutrient, Double> {
    val currentShares = calculateCaloryShares(current)
    val targetShares = calculateCaloryShares(target)

    return MacroNutrient.entries.associateWith { nutrient ->
        val targetShare = targetShares.getValue(nutrient)
        if (targetShare <= 0.0) 0.0 else currentShares.getValue(nutrient) / targetShare - 1
    }
}

/**
 * 많은 영양소와 부족한 영양소를 양이 아니라 구성으로 고른다. 탄단지가 칼로리에서 차지하는 비율(구성비)이 목표 구성비에서
 * [CALORY_SHARE_TOLERANCE]를 넘게 벗어난 것이 대상이다.
 *
 * 양으로 고르면 하루가 끝나기 전에는 모든 영양소가 목표보다 모자라 보인다.
 *
 * @param current 지금까지 먹은 양
 * @param target 목표 섭취량
 * @return 탄단지 기록이 없으면(합이 0) 구성비를 낼 수 없어 null
 */
fun calculateCaloryShareBalance(current: Nutrition, target: Nutrition): CaloryShareBalanceResult? {
    if (current.carbs + current.protein + current.fat <= 0) {
        return null
    }
    val deviations = calculateCaloryShareDeviations(current, target)

    return CaloryShareBalanceResult(
        excessNutrients = MacroNutrient.entries.filter { deviations.getValue(it) > CALORY_SHARE_TOLERANCE },
        lackingNutrients = MacroNutrient.entries.filter { deviations.getValue(it) < -CALORY_SHARE_TOLERANCE },
    )
}
