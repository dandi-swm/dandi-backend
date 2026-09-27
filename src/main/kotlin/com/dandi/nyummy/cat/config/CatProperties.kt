package com.dandi.nyummy.cat.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.cat")
data class CatProperties(val weightUpdateIntervalDays: Int, val weightUpdateTolerance: Double)
