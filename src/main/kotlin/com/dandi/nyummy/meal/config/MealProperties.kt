package com.dandi.nyummy.meal.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.meal")
data class MealProperties(
    val uploadMethod: String,
    val maxFileSizeBytes: Long,
    val presignedUrlExpirationMinutes: Int,
    val captureTimeTolerance: Duration,
)
