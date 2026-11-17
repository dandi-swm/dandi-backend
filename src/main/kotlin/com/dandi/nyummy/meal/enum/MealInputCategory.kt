package com.dandi.nyummy.meal.enum

/**
 * 인식이 판정한 입력 유형. 사용자가 올린 사진이 무엇을 찍은 것인지다.
 */
enum class MealInputCategory {

    // 실제 음식 사진. 먹기 전, 먹는 중, 조금 남은 음식.
    FOOD_PHOTO,

    // 음식 그림이나 모형.
    ILLUSTRATION,

    // 과자·음료·간편식 같은 포장이나 제품. 그 제품의 영양성분표도 여기에 든다.
    PACKAGE,

    // 글. 영수증, 주문·배달 앱 화면, 메뉴판, 먹은 것을 적은 메모.
    TEXT_RECORD,

    // 숫자가 보이는 영양성분표. 인식이 더는 이 값을 내지 않고 PACKAGE로 판정한다. 예전에 저장된 행을 읽으려고 남겨 둔다.
    NUTRITION_LABEL,

    // 음식과 관련 없는 사진, 무엇인지 알아볼 수 없는 사진.
    NOT_FOOD,
    ;

    /**
     * 포장 제품 유형인지([PACKAGE]와, 예전에 따로 판정하던 [NUTRITION_LABEL]). 음식 줄 이름이 제품 이름이라
     * 이 유형의 줄은 일반적인 1인분이 아니라 그 제품의 영양성분표 값을 구하고, 음식 이름별 1인분 영양(food_nutritions)을 거치지 않는다.
     */
    val isPackage: Boolean
        get() = this == PACKAGE || this == NUTRITION_LABEL

    /**
     * 음식으로 인식한 유형인지([NOT_FOOD]가 아닌 모든 유형). 식사의 입력 유형은 음식으로 인식될 때만 이 유형이 되고
     * 다시 분석해도 지워지지 않으므로, 식사의 입력 유형이 이 유형이면 음식으로 인식된 적이 있는 식사다.
     * 식사 기록 보상 상자를 이미 받은 식사인지를 이것으로 가린다.
     */
    val isFood: Boolean
        get() = this != NOT_FOOD
}
