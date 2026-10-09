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

    /**
     * 디바이스 토큰을 등록한다. 멀티 로그인을 허용하지 않으므로 사용자당 한 행만 둔다.
     *
     * 같은 토큰이 다른 사용자에 묶여 있으면 먼저 끊는다 — 로그아웃 없이 앱을 지운 뒤 같은 기기에서
     * 다른 계정이 로그인하면 이전 사용자의 알림이 새 사용자 기기로 가기 때문이다.
     *
     * 기존 행이 있으면 토큰만 교체하므로 앱이 실행마다 호출해도 된다(멱등).
     */
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
     * 서비스 알림 한 건을 보낸다. 수신 거부거나 기기 토큰이 없으면 발송하지 않고 빈 결과를 돌려준다.
     */
    fun sendServicePush(userId: Long, message: PushMessage): PushResult {
        val token = deviceTokenRepository.getServicePushToken(userId) ?: return PushResult.EMPTY

        return sendPushes(mapOf(token to message))
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

    /**
     * 죽은 토큰을 삭제한다. 발송 결과에서 [PushResult.invalidTokens]로 분류된 것만 넘겨야 한다.
     *
     * 일시 장애 토큰을 넘기면 멀쩡한 사용자의 알림이 영구히 끊긴다.
     */
    @Transactional
    fun deleteInvalidDeviceTokens(tokens: List<String>) {
        if (tokens.isEmpty()) {
            return
        }

        val deletedCount = deviceTokenRepository.deleteByTokenIn(tokens)

        logger.info("무효 디바이스 토큰 삭제: count={}", deletedCount)
    }
}
