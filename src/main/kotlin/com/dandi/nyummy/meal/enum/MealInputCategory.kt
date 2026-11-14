package com.dandi.nyummy.meal.enum

/**
 * 인식이 판정한 입력 유형. 사용자가 올린 사진이 무엇을 찍은 것인지다.
 */
enum class MealInputCategory {

    // 실제 음식 사진. 먹기 전, 먹는 중, 조금 남은 음식.
    FOOD_PHOTO,

    // 음식 그림이나 모형.
    ILLUSTRATION,

    // 과자·음료·간편식 같은 포장이나 제품.
    PACKAGE,

    // 글. 영수증, 주문·배달 앱 화면, 메뉴판, 먹은 것을 적은 메모.
    TEXT_RECORD,

    // 숫자가 보이는 영양성분표.
    NUTRITION_LABEL,

    // 음식과 관련 없는 사진, 무엇인지 알아볼 수 없는 사진.
    NOT_FOOD,
}
