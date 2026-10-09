package com.dandi.nyummy.infra.ai

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(AiProperties::class)
class AiConfig {

    @Bean
    fun aiRestClient(aiProperties: AiProperties): RestClient {
        val settings = HttpClientSettings.defaults()
            .withConnectTimeout(aiProperties.connectTimeout)
            .withReadTimeout(aiProperties.readTimeout)

        return RestClient.builder()
            .baseUrl(aiProperties.baseUrl)
            // JDK 구현은 응답 본문 수신까지 read-timeout으로 제한한다.
            .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
            .build()
    }
}
