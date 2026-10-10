package com.dandi.nyummy.meal.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.meal")
data class MealProperties(val uploadMethod: String, val maxFileSizeBytes: Long, val presignedUrlExpirationMinutes: Int)
