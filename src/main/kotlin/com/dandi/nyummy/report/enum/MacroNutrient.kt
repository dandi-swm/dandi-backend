package com.dandi.nyummy.report.enum

/**
 * 탄단지 세 영양소. [label]은 서버가 만드는 리포트 문장에 들어간다(셋 다 받침이 있어 조사는 "이"다).
 */
enum class MacroNutrient(val label: String) {
    CARBS("탄수화물"),
    PROTEIN("단백질"),
    FAT("지방"),
}
