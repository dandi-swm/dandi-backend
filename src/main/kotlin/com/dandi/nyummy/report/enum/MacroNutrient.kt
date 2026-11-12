package com.dandi.nyummy.report.enum

/**
 * 탄단지 세 영양소. 리포트 문장과 프롬프트에 쓰는 이름은 서버 고정 문구 파일의 `macroNutrient.<이름>`에 있다.
 */
enum class MacroNutrient {
    CARBS,
    PROTEIN,
    FAT,
}
