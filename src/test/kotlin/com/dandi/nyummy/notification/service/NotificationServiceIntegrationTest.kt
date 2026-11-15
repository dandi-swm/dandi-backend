package com.dandi.nyummy.notification.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.notification.dto.CreateDeviceTokenRequest
import com.dandi.nyummy.notification.entity.DeviceToken
import com.dandi.nyummy.notification.entity.PushHistory
import com.dandi.nyummy.notification.enum.DevicePlatform
import com.dandi.nyummy.notification.enum.PushOutcome
import com.dandi.nyummy.notification.repository.DeviceTokenRepository
import com.dandi.nyummy.notification.repository.PushHistoryJdbcRepository
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
import java.time.Instant
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
    private val pushHistoryJdbcRepository: PushHistoryJdbcRepository,
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

    // 전송 내역 JDBC 저장 (PushHistoryJdbcRepository). SQL에 적은 컬럼 이름과 값 변환은 ddl-auto: validate가 검사하지 않는다.

    @Test
    fun `여러 전송 내역을 한 번에 저장하면 JPA로 읽은 값이 넘긴 값과 같다`() {
        val iosUserId = createUser()
        val unknownPlatformUserId = createUser()
        val sentAt = Instant.parse("2026-11-13T03:00:00Z")

        pushHistoryJdbcRepository.createPushHistories(
            listOf(
                createPushHistory(iosUserId, DevicePlatform.IOS, PushOutcome.SUCCESS, sentAt),
                createPushHistory(unknownPlatformUserId, null, PushOutcome.INVALID_TOKEN, sentAt),
            ),
        )

        val pushHistoriesByUserId = pushHistoryRepository.findAll()
            .filter { it.userId in setOf(iosUserId, unknownPlatformUserId) }
            .associateBy { it.userId }
        assertEquals(2, pushHistoriesByUserId.size)

        val iosPushHistory = pushHistoriesByUserId.getValue(iosUserId)
        assertEquals("meal-reminder:$iosUserId:2026-11-13:LUNCH", iosPushHistory.notificationId)
        assertEquals(PushType.MEAL_REMINDER, iosPushHistory.category)
        assertEquals(DevicePlatform.IOS, iosPushHistory.platform)
        assertEquals("집사, 점심은 먹었어? 🐾", iosPushHistory.title)
        assertEquals("치즈 너무 배고프다냥. 사진 한 장만 찍어줘!", iosPushHistory.body)
        assertEquals(PushOutcome.SUCCESS, iosPushHistory.outcome)
        assertEquals(sentAt, iosPushHistory.sentAt)

        val unknownPlatformPushHistory = pushHistoriesByUserId.getValue(unknownPlatformUserId)
        assertEquals(null, unknownPlatformPushHistory.platform)
        assertEquals(PushOutcome.INVALID_TOKEN, unknownPlatformPushHistory.outcome)
    }

    @Test
    fun `저장할 전송 내역이 없으면 아무것도 저장하지 않는다`() {
        val countBefore = pushHistoryRepository.count()

        pushHistoryJdbcRepository.createPushHistories(emptyList())

        assertEquals(countBefore, pushHistoryRepository.count())
    }

    private fun createUser(): Long = userRepository.save(
        User(provider = AuthProvider.EMAIL, email = "${UUID.randomUUID()}@nyummy.co.kr"),
    ).id

    private fun createPushHistory(userId: Long, platform: DevicePlatform?, outcome: PushOutcome, sentAt: Instant) =
        PushHistory(
            userId = userId,
            notificationId = "meal-reminder:$userId:2026-11-13:LUNCH",
            category = PushType.MEAL_REMINDER,
            platform = platform,
            title = "집사, 점심은 먹었어? 🐾",
            body = "치즈 너무 배고프다냥. 사진 한 장만 찍어줘!",
            outcome = outcome,
            sentAt = sentAt,
        )
}
