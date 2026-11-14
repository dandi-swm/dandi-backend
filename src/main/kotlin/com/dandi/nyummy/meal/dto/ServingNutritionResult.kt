package com.dandi.nyummy.meal.dto

/**
 * 음식 하나의 1인분 조회 결과.
 *
 * @property iconId 이 음식에 맞는 아이콘
 */
data class ServingNutritionResult(val servingNutrition: ServingNutrition, val iconId: Long)
