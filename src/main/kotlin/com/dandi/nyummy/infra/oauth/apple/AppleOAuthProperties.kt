package com.dandi.nyummy.infra.oauth.apple

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.oauth.apple")
class AppleOAuthProperties(
    val issuer: String,
    val jwkSetUri: String,
    val clientIds: List<String>,
    val connectTimeout: Duration,
    val readTimeout: Duration,
)
