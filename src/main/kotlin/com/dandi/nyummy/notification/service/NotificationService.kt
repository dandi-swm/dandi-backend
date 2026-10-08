package com.dandi.nyummy.notification.service

import com.dandi.nyummy.infra.push.PushClient
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushResult
import com.dandi.nyummy.notification.dto.CreateDeviceTokenRequest
import com.dandi.nyummy.notification.entity.DeviceToken
import com.dandi.nyummy.notification.repository.DeviceTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationService(
    private val deviceTokenRepository: DeviceTokenRepository,
    private val pushClient: PushClient,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(NotificationService::class.java)
    }

    @Transactional
    fun createDeviceToken(userId: Long, request: CreateDeviceTokenRequest) {
        deviceTokenRepository.deleteByTokenAndNotUserId(request.token, userId)

        val existing = deviceTokenRepository.getDeviceTokenByUserId(userId)

        if (existing != null) {
            existing.updateToken(request.token, request.platform)
            return
        }

        val newToken = DeviceToken(
            request.token,
            request.platform,
            userId,
        )

        deviceTokenRepository.save(newToken)
    }

    /**
     * 발송만 하고 결과를 돌려준다. 무효 토큰 정리는 호출자가 [deleteInvalidDeviceTokens]로 한다.
     *
     * 트랜잭션을 걸지 않는다. FCM 호출은 수 초 걸리는 외부 I/O라, 트랜잭션 안에서 하면
     * 그만큼 DB 커넥션을 잡고 있는다.
     */
    fun sendPushes(messagesByToken: Map<String, PushMessage>): PushResult {
        if (messagesByToken.isEmpty()) {
            return PushResult.EMPTY
        }

        return pushClient.sendPushes(messagesByToken)
    }

    @Transactional
    fun deleteInvalidDeviceTokens(tokens: List<String>) {
        if (tokens.isEmpty()) {
            return
        }

        val deletedCount = deviceTokenRepository.deleteByTokenIn(tokens)

        logger.info("무효 디바이스 토큰 삭제: count={}", deletedCount)
    }
}
