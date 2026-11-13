package com.dandi.nyummy.worker.dto

import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.enum.MealInputCategory
import java.math.BigDecimal
import java.time.Instant

/**
 * 영양 작업이 영양을 계산할 식사. 트랜잭션 밖에서 1인분을 조회하고 코멘트를 만들 때 쓰므로 엔티티가 아닌 값만 담는다.
 *
 * @property mealAt 식사를 먹은 시각. 코멘트에 넣을 같은 날의 앞선 식사를 고를 때 쓴다
 * @property inputCategory 식사의 입력 유형. 1인분을 조회하는 방법이 유형에 따라 다르다
 * @property hasCatComment 이미 고양이 코멘트가 있는지. 코멘트는 식사당 한 번만 만든다
 * @property mealFoods 식사의 음식 줄(순서대로)
 */
data class AnalysisMealResult(
    val userId: Long,
    val mealAt: Instant,
    val inputCategory: MealInputCategory,
    val hasCatComment: Boolean,
    val mealFoods: List<AnalysisMealFoodResult>,
)

/**
 * 영양을 계산할 식사의 음식 한 줄.
 *
 * @property portion 인분
 * @property servingNutrition 지금 저장된 1인분 영양. 아직 구하지 않았거나 다시 구해야 하면 null
 * @property iconId 지금 저장된 아이콘. 아직 구하지 않았거나 다시 구해야 하면 null
 */
data class AnalysisMealFoodResult(
    val mealFoodId: Long,
    val name: String,
    val portion: BigDecimal,
    val servingNutrition: ServingNutrition?,
    val iconId: Long?,
)
