package com.dandi.nyummy.infra.push

import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushResult
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * FCM이 꺼진 환경(`app.fcm.enabled=false`)에서 쓰는 임시 대역.
 *
 * 발송하지 않고 대상 수만 로그로 남긴다.
 * 프로덕션 코드의 임시 대역이므로 Stub 접두를 쓴다.
 */
@Component
@ConditionalOnProperty(prefix = "app.fcm", name = ["enabled"], havingValue = "false")
class StubPushClient : PushClient {
    companion object {
        private val logger = LoggerFactory.getLogger(StubPushClient::class.java)
    }

    override fun sendPushes(messagesByToken: Map<String, PushMessage>): PushResult {
        logger.info("FCM이 비활성화되어 발송하지 않습니다: messageCount={}", messagesByToken.size)

        return PushResult(
            successCount = messagesByToken.size,
            invalidTokens = emptyList(),
            retryableTokens = emptyList(),
        )
    }
}
