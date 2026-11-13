package com.dandi.nyummy.meal.dto

import java.math.BigDecimal
import java.time.LocalTime

/**
 * 고양이 코멘트를 만들 재료.
 *
 * @property catName 고양이 이름
 * @property mealFoods 방금 기록한 식사의 음식 줄
 * @property earlierMeals 같은 날 이 식사보다 앞선 완료 식사. 먹은 순서다
 * @property current 오늘 지금까지 먹은 양. 방금 기록한 식사를 포함한다
 * @property target 하루 목표 섭취량
 * @property caloryShareBalance 오늘 지금까지의 많은·부족한 영양소. 탄단지 기록이 없으면 null
 */
data class CatCommentInput(
    val catName: String,
    val mealFoods: List<CatCommentMealFoodInput>,
    val earlierMeals: List<CatCommentMealInput>,
    val current: Nutrition,
    val target: Nutrition,
    val caloryShareBalance: CaloryShareBalanceResult?,
)

/**
 * 방금 기록한 식사의 음식 한 줄.
 *
 * @property portion 인분
 * @property nutrition 줄 영양(1인분 × 인분)
 */
data class CatCommentMealFoodInput(val name: String, val portion: BigDecimal, val nutrition: Nutrition)

/**
 * 앞선 식사 하나.
 *
 * @property mealAt 먹은 시각. 사용자에게 보이는 시간대(Asia/Seoul)로 바꾼 값이다
 * @property nutrition 식사 합계
 */
data class CatCommentMealInput(val mealAt: LocalTime, val name: String, val nutrition: Nutrition)
