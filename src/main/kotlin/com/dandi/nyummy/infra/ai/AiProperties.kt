package com.dandi.nyummy.infra.ai

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Gemini 설정. app.ai.enabled가 켜진 서비스(기본은 Worker)에서만 바인딩한다.
 * [com.dandi.nyummy.NyummyApplication]의 설정 스캔이 이 클래스를 따로 등록하므로, [AiConfig]와 같은 조건을 여기에도 붙여야
 * API 서비스가 GEMINI_API_KEY 없이 뜬다.
 */
@ConditionalOnBooleanProperty("app.ai.enabled")
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
