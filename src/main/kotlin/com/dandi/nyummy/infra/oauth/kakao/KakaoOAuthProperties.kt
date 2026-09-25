package com.dandi.nyummy.infra.oauth.kakao

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.oauth.kakao")
class KakaoOAuthProperties(
    val issuer: String,
    val jwkSetUri: String,
    val appKey: String,
    val connectTimeout: Duration,
    val readTimeout: Duration,
)
