package com.dandi.nyummy.meal.dto

import java.math.BigDecimal

/**
 * 음식 1인분의 영양. 인분을 곱해 줄 영양을 구하고, 사용자가 고친 줄 영양을 인분으로 나눠 다시 구하므로 소수로 둔다.
 */
data class ServingNutrition(val calory: BigDecimal, val carbs: BigDecimal, val protein: BigDecimal, val fat: BigDecimal)
