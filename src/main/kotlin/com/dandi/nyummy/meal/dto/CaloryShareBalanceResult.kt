package com.dandi.nyummy.meal.dto

import com.dandi.nyummy.meal.enum.MacroNutrient

/**
 * 탄단지의 칼로리 구성비를 목표 구성비와 비교한 결과. 둘 다 비어 있으면 균형이 좋은 것이다.
 *
 * @property excessNutrients 목표 구성비보다 많은 영양소
 * @property lackingNutrients 목표 구성비보다 부족한 영양소
 */
data class CaloryShareBalanceResult(val excessNutrients: List<MacroNutrient>, val lackingNutrients: List<MacroNutrient>)
