package com.dandi.nyummy.internal.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.internal")
data class InternalProperties(val batchKey: String)
