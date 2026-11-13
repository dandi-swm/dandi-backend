package com.dandi.nyummy.meal.enum

/**
 * v2 식사의 분석 작업 종류. v1 분석 작업은 종류가 없다(null).
 */
enum class MealAnalysisJobCategory {

    // 사진에서 입력 유형, 대표 이름, 음식 줄을 인식한다.
    RECOGNITION,

    // 음식 줄의 1인분 영양을 구해 식사 영양을 계산한다.
    NUTRITION,
}
