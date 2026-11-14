package com.dandi.nyummy.infra.discord

import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * 운영자 채널에 Discord Webhook으로 메시지를 보낸다.
 *
 * Discord는 외부 서비스라 보낸 내용이 그대로 밖에 남는다. 사용자가 쓴 글(문의 제목·본문 등)은 넣지 않는다.
 * Webhook URL에 토큰이 들어 있으므로 로그에 남기지 않는다.
 */
class DiscordClient(private val restClient: RestClient, private val webhookUrl: String) {

    /**
     * 메시지를 보낸다. Webhook URL이 비어 있는 환경(local·test 등)에서는 보내지 않는다.
     *
     * @throws RestClientException 연결·응답 시간을 넘겼거나 Discord가 4xx·5xx로 답한 경우
     */
    fun sendMessage(content: String) {
        if (webhookUrl.isBlank()) {
            return
        }

        restClient.post()
            .uri(webhookUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("content" to content))
            .retrieve()
            .toBodilessEntity()
    }
}
