package com.dandi.nyummy.meal.config

/**
 * 제품 이름·용량별 영양(package_nutritions) 설정. app.meal.package-nutrition에 둔다.
 *
 * @property tolerance 모델이 구한 값이 사진에 적힌 값과 벗어나도 되는 비율(0.1 = ±10%). 넘으면 불일치로 기록한다.
 *   사진 값을 저장할지는 이 값과 상관없다
 */
data class PackageNutritionProperties(val tolerance: Double) {
    // 설정이 틀린 채로 모든 값이 불일치로 쌓이거나 하나도 쌓이지 않는 것을 앱이 뜰 때 막는다.
    init {
        require(tolerance in 0.0..1.0) {
            "app.meal.package-nutrition.tolerance는 0과 1 사이여야 합니다: $tolerance"
        }
    }
}
