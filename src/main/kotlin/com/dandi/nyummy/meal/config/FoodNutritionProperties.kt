package com.dandi.nyummy.meal.config

import java.time.Duration

/**
 * 음식 이름별 1인분 영양(food_nutritions) 설정. app.meal.food-nutrition에 둔다.
 *
 * @property timeToLive 저장한 값을 다시 구하지 않고 쓰는 기간. 지난 값은 없는 것으로 보고 새로 구해 바꾼다
 * @property caloryTolerance 저장할 값의 칼로리가 탄단지로 계산한 칼로리와 벗어나도 되는 비율(0.1 = ±10%). 넘으면 저장하지 않는다
 */
data class FoodNutritionProperties(val timeToLive: Duration, val caloryTolerance: Double) {
    // 설정이 틀린 채로 모든 값이 바로 만료되거나 검증 없이 저장되지 않도록 앱이 뜰 때 막는다.
    init {
        require(!timeToLive.isNegative && !timeToLive.isZero) {
            "app.meal.food-nutrition.time-to-live는 0보다 커야 합니다: $timeToLive"
        }
        require(caloryTolerance in 0.0..1.0) {
            "app.meal.food-nutrition.calory-tolerance는 0과 1 사이여야 합니다: $caloryTolerance"
        }
    }
}
