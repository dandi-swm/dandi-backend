package com.dandi.nyummy.meal.enum

/**
 * 식사를 어느 분석으로 만들었는지. v2 API가 식사마다 내려주고, 앱은 이 값으로 상세 화면을 고른다.
 */
enum class MealAnalysisVersion {

    // 지금 분석. Gemini 한 번으로 식사 합계를 낸다. 음식 줄이 없다.
    V1,

    // AI 고도화 분석. 인식한 음식 줄마다 영양을 계산한다.
    V2,
}
