package com.dandi.nyummy.meal.enum

/**
 * 제품의 총 내용량(용량) 단위. kg과 L는 g과 ml로 바꿔 저장하므로 둘뿐이다.
 */
enum class NetContentUnit {

    // 그램. 과자·간편식처럼 무게로 적는 제품.
    G,

    // 밀리리터. 음료처럼 부피로 적는 제품.
    ML,
}
