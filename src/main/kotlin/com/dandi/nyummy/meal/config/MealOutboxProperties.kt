package com.dandi.nyummy.meal.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.meal.outbox")
data class MealOutboxProperties(val recoveryDelay: Duration = Duration.ofSeconds(3), val batchSize: Int = 100) {
    init {
        require(!recoveryDelay.isNegative && recoveryDelay.toMillis() > 0)
        require(batchSize > 0)
    }
}
