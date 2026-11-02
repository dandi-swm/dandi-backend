package com.dandi.nyummy.infra.ai

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestClient

// 영양 분석을 하는 서비스(기본은 Worker)에서만 Gemini 연결 풀을 만든다. API 서비스에는 Gemini 호출이 없다.
@Configuration
@ConditionalOnBooleanProperty("app.ai.enabled")
@EnableConfigurationProperties(AiProperties::class)
class AiConfig {

    // Boot가 주입하는 Builder를 써야 관측(http.client.requests 메트릭·span)이 붙는다.
    // RestClient.builder()로 직접 만들면 trace에서 Gemini 호출 구간이 빈칸으로 남는다.
    //
    // 연결 풀은 직접 만든다. 기본값(route당 5·전체 25·연결 대기 3분)은 task당 Gemini 동시 5건으로 식사 기록을 묶었고,
    // 넘친 요청이 연결을 3분 기다리며 Tomcat 스레드를 쥐어 서비스 전체가 무너졌다(부하 테스트 journey-100tps-20261021-01).
    // Gemini는 호스트가 하나라 route당 = 전체로 둔다. 연결 대기 + readTimeout은 ALB idle timeout 60초보다 짧아야 한다.
    @Bean
    fun aiRestClient(
        aiProperties: AiProperties,
        restClientBuilder: RestClient.Builder,
        meterRegistry: MeterRegistry,
    ): RestClient {
        val connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(aiProperties.maxConnections)
            .setMaxConnPerRoute(aiProperties.maxConnections)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.of(aiProperties.connectTimeout))
                    .setSocketTimeout(Timeout.of(aiProperties.readTimeout))
                    .build(),
            )
            .build()
        // task별 풀 사용량(httpcomponents.httpclient.pool.*, 태그 httpclient=gemini). gauge라 내보낼 때만 재서 짧은 포화는 놓칠 수 있다.
        PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, "gemini").bindTo(meterRegistry)

        val httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.of(aiProperties.connectionRequestTimeout))
                    .build(),
            )
            .build()

        return restClientBuilder
            .baseUrl(aiProperties.baseUrl)
            .requestFactory(HttpComponentsClientHttpRequestFactory(httpClient))
            .build()
    }
}
