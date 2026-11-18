package com.dandi.nyummy.meal.enum

/**
 * 사진에 적힌 제품의 값이 무엇과 달랐는지.
 */
enum class PackageNutritionMismatchCategory {

    // 저장해 둔 값(package_nutritions)과 달랐다. 네 값이 다 적힌 사진이면 저장해 둔 값을 사진 값으로 덮어쓴다.
    SAVED,

    // 저장해 둔 값이 없어 이름만으로 모델에 물었는데, 그 값이 사진 값과 달랐다. 모델 값은 저장하지 않는다.
    NAMED,
}
