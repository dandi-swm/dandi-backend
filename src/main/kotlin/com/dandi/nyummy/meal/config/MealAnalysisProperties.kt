package com.dandi.nyummy.meal.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.meal.analysis")
data class MealAnalysisProperties(val pollDelay: Duration = Duration.ofSeconds(1), val concurrency: Int = 2) {
    init {
        require(!pollDelay.isNegative && pollDelay.toMillis() > 0)
        require(concurrency > 0)
    }
}
