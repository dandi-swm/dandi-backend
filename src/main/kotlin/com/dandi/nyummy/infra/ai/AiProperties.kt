package com.dandi.nyummy.infra.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Gemini 설정. 모든 서비스에서 바인딩하므로 GEMINI_API_KEY가 없으면 어느 서비스도 뜨지 않는다.
 */
@ConfigurationProperties(prefix = "app.ai")
class AiProperties(
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val connectTimeout: Duration,
    val readTimeout: Duration,
    val maxConnections: Int,
    val connectionRequestTimeout: Duration,
)
