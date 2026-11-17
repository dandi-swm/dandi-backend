package com.dandi.nyummy.meal.dto

import java.math.BigDecimal

/**
 * 음식 하나의 1인분 조회 결과.
 *
 * @property iconId 이 음식에 맞는 아이콘
 * @property portion 사진에 보이는 양이 1인분의 몇 배인지. 사진을 함께 보낸 조회만 채우고, 이름만으로 구한 조회는 null이다
 */
data class ServingNutritionResult(
    val servingNutrition: ServingNutrition,
    val iconId: Long,
    val portion: BigDecimal? = null,
)

/**
 * 사진을 함께 보내 영양을 구할 음식 한 줄.
 *
 * @property nutritionHint 사진에 이 음식의 값으로 적혀 있던 영양. 일부만 있는 줄만 여기로 온다(넷 다 있으면 구할 것이 없다)
 */
data class PhotoServingNutritionInput(val name: String, val nutritionHint: NutritionHint? = null)

/**
 * 사진을 함께 보낸 1인분 조회의 결과.
 *
 * @property servingNutritions 보낸 음식과 같은 순서, 같은 개수의 결과
 * @property totalNutritionHint 사진에 식사 전체의 값으로 적혀 있던 영양. 음식 하나의 값이 아니라 합계이고, 적힌 것이 없으면 null이다
 */
data class PhotoServingNutritionResult(
    val servingNutritions: List<ServingNutritionResult>,
    val totalNutritionHint: NutritionHint? = null,
)
