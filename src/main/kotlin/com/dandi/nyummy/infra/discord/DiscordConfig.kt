package com.dandi.nyummy.infra.discord

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(DiscordProperties::class)
class DiscordConfig {

    /**
     * 운영자 채널에 메시지를 보내는 Discord 클라이언트.
     *
     * RestClient를 빈으로 노출하지 않는다 — RestClient를 타입만으로 주입받는 곳(GeminiNutritionAnalysisClient)이 있어
     * 빈이 둘이 되면 주입이 모호해진다.
     */
    @Bean
    fun discordClient(properties: DiscordProperties): DiscordClient {
        val settings = HttpClientSettings.defaults()
            .withConnectTimeout(properties.connectTimeout)
            .withReadTimeout(properties.readTimeout)

        val restClient = RestClient.builder()
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .build()

        return DiscordClient(restClient, properties.webhookUrl)
    }
}
