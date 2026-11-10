package com.dandi.nyummy.report.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * @property listPrice 리포트 가격(코인). 첫 리포트는 무료(0)이고 그 뒤로 이 가격이다
 * @property minMealCount 그 주에 분석이 완료된 식사가 이만큼 있어야 리포트를 살 수 있다
 * @property generationTimeToLive 구매 뒤 이 시간 안에 생성이 끝나야 한다. 넘긴 리포트는 대조 배치가 찾아 알린다
 */
@ConfigurationProperties("app.report")
data class ReportProperties(
    val listPrice: Int = 100,
    val minMealCount: Int = 3,
    val generationTimeToLive: Duration = Duration.ofHours(1),
) {
    init {
        require(listPrice > 0) { "app.report.list-price는 0보다 커야 합니다: $listPrice" }
        require(minMealCount > 0) { "app.report.min-meal-count는 0보다 커야 합니다: $minMealCount" }
    }
}
