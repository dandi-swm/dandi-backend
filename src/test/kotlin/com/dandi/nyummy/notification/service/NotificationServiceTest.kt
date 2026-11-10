package com.dandi.nyummy.notification.service

import com.dandi.nyummy.infra.push.PushClient
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushResult
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.notification.dto.CreateDeviceTokenRequest
import com.dandi.nyummy.notification.entity.DeviceToken
import com.dandi.nyummy.notification.entity.PushHistory
import com.dandi.nyummy.notification.enum.DevicePlatform
import com.dandi.nyummy.notification.enum.PushOutcome
import com.dandi.nyummy.notification.repository.DeviceTokenRepository
import com.dandi.nyummy.notification.repository.PushHistoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class NotificationServiceTest {

    private val now = Instant.parse("2026-11-03T03:00:00Z")

    private val deviceTokenRepository = mockk<DeviceTokenRepository>()
    private val pushHistoryRepository = mockk<PushHistoryRepository>()
    private val pushClient = mockk<PushClient>()

    private val notificationService = NotificationService(
        deviceTokenRepository = deviceTokenRepository,
        pushHistoryRepository = pushHistoryRepository,
        pushClient = pushClient,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `처음 등록하는 기기 토큰은 요청 언어와 함께 저장한다`() {
        val request = CreateDeviceTokenRequest(token = "new-token", platform = DevicePlatform.ANDROID)
        every { deviceTokenRepository.deleteByTokenAndNotUserId("new-token", 1L) } returns 0
        every { deviceTokenRepository.findDeviceTokenByUserId(1L) } returns null
        val savedToken = slot<DeviceToken>()
        every { deviceTokenRepository.save(capture(savedToken)) } answers { firstArg() }

        notificationService.createDeviceToken(1L, request, Language.JA)

        assertEquals("new-token", savedToken.captured.token)
        assertEquals(DevicePlatform.ANDROID, savedToken.captured.platform)
        assertEquals(1L, savedToken.captured.userId)
        assertEquals(Language.JA, savedToken.captured.language)
    }

    @Test
    fun `이미 등록한 기기 토큰은 새 행을 만들지 않고 토큰과 언어를 바꾼다`() {
        val existing = DeviceToken("old-token", DevicePlatform.ANDROID, 1L, Language.KO)
        val request = CreateDeviceTokenRequest(token = "new-token", platform = DevicePlatform.IOS)
        every { deviceTokenRepository.deleteByTokenAndNotUserId("new-token", 1L) } returns 0
        every { deviceTokenRepository.findDeviceTokenByUserId(1L) } returns existing

        notificationService.createDeviceToken(1L, request, Language.EN)

        assertEquals("new-token", existing.token)
        assertEquals(DevicePlatform.IOS, existing.platform)
        assertEquals(Language.EN, existing.language)
        verify(exactly = 0) { deviceTokenRepository.save(any()) }
    }

    @Test
    fun `언어를 몰랐던 기존 기기 토큰도 등록할 때 언어가 채워진다`() {
        val existing = DeviceToken("token", DevicePlatform.ANDROID, 1L, language = null)
        val request = CreateDeviceTokenRequest(token = "token", platform = DevicePlatform.ANDROID)
        every { deviceTokenRepository.deleteByTokenAndNotUserId("token", 1L) } returns 0
        every { deviceTokenRepository.findDeviceTokenByUserId(1L) } returns existing

        notificationService.createDeviceToken(1L, request, Language.KO)

        assertEquals(Language.KO, existing.language)
    }

    @Test
    fun `발송 결과를 토큰별로 분류해 전송 내역으로 저장한다`() {
        val messagesByToken = mapOf(
            "success-token" to createPushMessage(userId = 1L),
            "invalid-token" to createPushMessage(userId = 2L),
            "retryable-token" to createPushMessage(userId = 3L),
        )
        every { deviceTokenRepository.findDeviceTokensByTokenIn(messagesByToken.keys) } returns emptyList()
        every { pushClient.sendPushes(messagesByToken) } returns PushResult(
            successCount = 1,
            invalidTokens = listOf("invalid-token"),
            retryableTokens = listOf("retryable-token"),
        )
        val pushHistories = slot<List<PushHistory>>()
        every { pushHistoryRepository.saveAll(capture(pushHistories)) } answers { firstArg() }

        notificationService.sendPushes(messagesByToken)

        assertEquals(
            mapOf(
                1L to PushOutcome.SUCCESS,
                2L to PushOutcome.INVALID_TOKEN,
                3L to PushOutcome.RETRYABLE,
            ),
            pushHistories.captured.associate { it.userId to it.outcome },
        )
    }

    @Test
    fun `전송 내역에 보낸 내용과 플랫폼과 발송 시각을 남긴다`() {
        val message = createPushMessage(userId = 1L)
        val messagesByToken = mapOf("token" to message)
        every { deviceTokenRepository.findDeviceTokensByTokenIn(messagesByToken.keys) } returns
            listOf(DeviceToken("token", DevicePlatform.IOS, 1L, Language.KO))
        every { pushClient.sendPushes(messagesByToken) } returns PushResult(1, emptyList(), emptyList())
        val pushHistories = slot<List<PushHistory>>()
        every { pushHistoryRepository.saveAll(capture(pushHistories)) } answers { firstArg() }

        notificationService.sendPushes(messagesByToken)

        val pushHistory = pushHistories.captured.single()
        assertEquals(message.notificationId, pushHistory.notificationId)
        assertEquals(PushType.MEAL_REMINDER, pushHistory.category)
        assertEquals(DevicePlatform.IOS, pushHistory.platform)
        assertEquals(message.title, pushHistory.title)
        assertEquals(message.body, pushHistory.body)
        assertEquals(now, pushHistory.sentAt)
    }

    @Test
    fun `기기 토큰을 찾지 못해도 플랫폼 없이 전송 내역을 남긴다`() {
        val messagesByToken = mapOf("token" to createPushMessage(userId = 1L))
        every { deviceTokenRepository.findDeviceTokensByTokenIn(messagesByToken.keys) } returns emptyList()
        every { pushClient.sendPushes(messagesByToken) } returns PushResult(1, emptyList(), emptyList())
        val pushHistories = slot<List<PushHistory>>()
        every { pushHistoryRepository.saveAll(capture(pushHistories)) } answers { firstArg() }

        notificationService.sendPushes(messagesByToken)

        assertNull(pushHistories.captured.single().platform)
    }

    @Test
    fun `전송 내역 저장이 실패해도 발송 결과를 돌려준다`() {
        val messagesByToken = mapOf("token" to createPushMessage(userId = 1L))
        val result = PushResult(0, listOf("token"), emptyList())
        every { deviceTokenRepository.findDeviceTokensByTokenIn(messagesByToken.keys) } returns emptyList()
        every { pushClient.sendPushes(messagesByToken) } returns result
        every { pushHistoryRepository.saveAll(any<List<PushHistory>>()) } throws
            DataAccessResourceFailureException("DB 장애")

        assertSame(result, notificationService.sendPushes(messagesByToken))
    }

    @Test
    fun `보낼 메시지가 없으면 발송도 저장도 하지 않는다`() {
        val result = notificationService.sendPushes(emptyMap())

        assertSame(PushResult.EMPTY, result)
        verify(exactly = 0) { pushClient.sendPushes(any()) }
        verify(exactly = 0) { pushHistoryRepository.saveAll(any<List<PushHistory>>()) }
    }

    private fun createPushMessage(userId: Long) = PushMessage(
        userId = userId,
        type = PushType.MEAL_REMINDER,
        notificationId = "meal-reminder:$userId:2026-11-03:LUNCH",
        title = "집사, 점심은 먹었어?",
        body = "사진 한 장만 찍어줘!",
        appLink = "nyummy://home",
    )
}
