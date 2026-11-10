package com.dandi.nyummy.notification.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.notification.dto.CreateDeviceTokenRequest
import com.dandi.nyummy.notification.entity.DeviceToken
import com.dandi.nyummy.notification.enum.DevicePlatform
import com.dandi.nyummy.notification.enum.PushOutcome
import com.dandi.nyummy.notification.repository.DeviceTokenRepository
import com.dandi.nyummy.notification.repository.PushHistoryRepository
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 전송 내역 저장을 실제 MySQL에서 돌린다. 저장 실패는 로그만 남기고 삼키므로, mock으로는 INSERT가 실패해도 알 수 없다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class NotificationServiceIntegrationTest @Autowired constructor(
    private val notificationService: NotificationService,
    private val userRepository: UserRepository,
    private val deviceTokenRepository: DeviceTokenRepository,
    private val pushHistoryRepository: PushHistoryRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4.9")
            .withDatabaseName("nyummy_test")
            .withUsername("test")
            .withPassword("test")
    }

    @Test
    fun `발송하면 전송 내역이 저장된다`() {
        val userId = userRepository.save(
            User(provider = AuthProvider.EMAIL, email = "${UUID.randomUUID()}@nyummy.co.kr"),
        ).id
        val token = UUID.randomUUID().toString()
        deviceTokenRepository.save(DeviceToken(token, DevicePlatform.ANDROID, userId, Language.KO))

        notificationService.sendPushes(
            mapOf(
                token to PushMessage(
                    userId = userId,
                    type = PushType.ANALYSIS_COMPLETED,
                    notificationId = "analysis:1:COMPLETED:1793674800000",
                    title = "냥".repeat(255),
                    body = "밥".repeat(255),
                    appLink = "nyummy://home",
                ),
            ),
        )

        val pushHistory = pushHistoryRepository.findAll().single { it.userId == userId }
        assertEquals(PushType.ANALYSIS_COMPLETED, pushHistory.category)
        assertEquals(DevicePlatform.ANDROID, pushHistory.platform)
        assertEquals("냥".repeat(255), pushHistory.title)
        assertEquals("밥".repeat(255), pushHistory.body)
        assertEquals(PushOutcome.SUCCESS, pushHistory.outcome)
    }

    @Test
    fun `기기 토큰을 다시 등록하면 DB의 언어가 마지막 요청 언어로 바뀐다`() {
        val userId = userRepository.save(
            User(provider = AuthProvider.EMAIL, email = "${UUID.randomUUID()}@nyummy.co.kr"),
        ).id
        val request = CreateDeviceTokenRequest(token = UUID.randomUUID().toString(), platform = DevicePlatform.ANDROID)

        notificationService.createDeviceToken(userId, request, Language.KO)
        notificationService.createDeviceToken(userId, request, Language.JA)

        assertEquals(Language.JA, deviceTokenRepository.findDeviceTokenByUserId(userId)?.language)
    }
}
