package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.dto.AppUpdatePushResponse
import com.dandi.nyummy.admin.dto.AppUpdatePushTargetResult
import com.dandi.nyummy.admin.dto.SendAppUpdatePushRequest
import com.dandi.nyummy.config.StoreLinkProperties
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushResult
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.internal.batch.repository.PushDeduplicationRepository
import com.dandi.nyummy.language.createTestMessageSource
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.enum.DevicePlatform
import com.dandi.nyummy.notification.repository.DeviceTokenRepository
import com.dandi.nyummy.notification.service.NotificationService
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.SliceImpl
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 앱 업데이트 알림의 묶음 발송·중복 방지·발송 가능 시각을 다룬다. 기본은 2026-11-22 14시(KST)에 Android 1.4.0을 보낸다.
 */
class AdminAppUpdatePushServiceTest {

    private val deviceTokenRepository = mockk<DeviceTokenRepository>()
    private val pushDeduplicationRepository = mockk<PushDeduplicationRepository> {
        every { createSentMarks(any(), any()) } answers { firstArg<List<String>>().toSet() }
        every { deleteSentMarks(any()) } just Runs
    }
    private val notificationService = mockk<NotificationService> {
        every { sendPushes(any()) } answers
            { PushResult(firstArg<Map<String, PushMessage>>().size, emptyList(), emptyList()) }
        every { deleteInvalidDeviceTokens(any()) } just Runs
    }
    private val messageSource = createTestMessageSource()

    private fun createService(now: String = "2026-11-22T14:00:00+09:00") = AdminAppUpdatePushService(
        deviceTokenRepository = deviceTokenRepository,
        pushDeduplicationRepository = pushDeduplicationRepository,
        notificationService = notificationService,
        storeLinkProperties = StoreLinkProperties(android = PLAY_STORE_LINK, ios = APP_STORE_LINK),
        messageSource = messageSource,
        clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC),
    )

    private val request = SendAppUpdatePushRequest(platform = DevicePlatform.ANDROID, appVersion = "1.4.0")

    private fun createTarget(userId: Long, language: Language? = null) =
        AppUpdatePushTargetResult(userId = userId, token = "token-$userId", language = language)

    private fun givenPage(
        cursor: Long,
        targets: List<AppUpdatePushTargetResult>,
        hasNext: Boolean,
        platform: DevicePlatform = DevicePlatform.ANDROID,
    ) {
        every {
            deviceTokenRepository.findAppUpdatePushTargetsAfterCursor(platform, cursor, any())
        } returns SliceImpl(targets, PageRequest.of(0, PAGE_SIZE), hasNext)
    }

    private fun captureMessages(): CapturingSlot<Map<String, PushMessage>> {
        val messagesByToken = slot<Map<String, PushMessage>>()
        every { notificationService.sendPushes(capture(messagesByToken)) } answers
            { PushResult(messagesByToken.captured.size, emptyList(), emptyList()) }
        return messagesByToken
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    @Test
    fun `다음 묶음이 있으면 직전 묶음의 마지막 userId를 커서로 다음 묶음을 조회한다`() {
        givenPage(cursor = 0, listOf(createTarget(1), createTarget(2)), hasNext = true)
        givenPage(cursor = 2, listOf(createTarget(3)), hasNext = false)

        createService().sendAppUpdatePushes(ADMIN_ID, request)

        verifyOrder {
            deviceTokenRepository.findAppUpdatePushTargetsAfterCursor(
                DevicePlatform.ANDROID,
                0,
                PageRequest.of(0, PAGE_SIZE),
            )
            deviceTokenRepository.findAppUpdatePushTargetsAfterCursor(
                DevicePlatform.ANDROID,
                2,
                PageRequest.of(0, PAGE_SIZE),
            )
        }
        verify(exactly = 2) { notificationService.sendPushes(any()) }
    }

    @Test
    fun `업데이트 알림은 누르면 플랫폼 스토어가 열린다`() {
        givenPage(cursor = 0, listOf(createTarget(1)), hasNext = false)
        val messagesByToken = captureMessages()

        createService().sendAppUpdatePushes(ADMIN_ID, request)

        val message = messagesByToken.captured.getValue("token-1")
        assertEquals(PushType.APP_UPDATE, message.type)
        assertEquals(PLAY_STORE_LINK, message.appLink)
        assertEquals(NotificationId.createAppUpdateId(1, DevicePlatform.ANDROID, "1.4.0"), message.notificationId)
    }

    @Test
    fun `문구는 기기 언어로 보낸다`() {
        givenPage(cursor = 0, listOf(createTarget(1, Language.JA)), hasNext = false)
        val messagesByToken = captureMessages()

        createService().sendAppUpdatePushes(ADMIN_ID, request)

        val message = messagesByToken.captured.getValue("token-1")
        assertEquals(messageSource.getMessage("push.appUpdate.title", Language.JA), message.title)
        assertEquals(messageSource.getMessage("push.appUpdate.body", Language.JA), message.body)
    }

    @Test
    fun `언어를 저장하기 전에 등록한 기기 토큰에는 원본 언어로 보낸다`() {
        givenPage(cursor = 0, listOf(createTarget(1, language = null)), hasNext = false)
        val messagesByToken = captureMessages()

        createService().sendAppUpdatePushes(ADMIN_ID, request)

        val message = messagesByToken.captured.getValue("token-1")
        assertEquals(messageSource.getMessage("push.appUpdate.title", Language.ORIGINAL_LANGUAGE), message.title)
    }

    @Test
    fun `iOS로 보내면 App Store 링크와 iOS 출시의 중복 방지 키로 보낸다`() {
        givenPage(cursor = 0, listOf(createTarget(1)), hasNext = false, platform = DevicePlatform.IOS)
        val messagesByToken = captureMessages()

        createService().sendAppUpdatePushes(
            ADMIN_ID,
            SendAppUpdatePushRequest(platform = DevicePlatform.IOS, appVersion = "1.4.0"),
        )

        val message = messagesByToken.captured.getValue("token-1")
        assertEquals(APP_STORE_LINK, message.appLink)
        assertEquals(NotificationId.createAppUpdateId(1, DevicePlatform.IOS, "1.4.0"), message.notificationId)
    }

    @Test
    fun `같은 출시로 이미 받은 사용자는 보내지 않고 건수에서 뺀다`() {
        givenPage(cursor = 0, listOf(createTarget(1), createTarget(2)), hasNext = false)
        val notSentId = NotificationId.createAppUpdateId(2, DevicePlatform.ANDROID, "1.4.0")
        every { pushDeduplicationRepository.createSentMarks(any(), Duration.ofDays(7)) } answers
            { firstArg<List<String>>().toSet() - notSentId }
        val messagesByToken = captureMessages()

        val response = createService().sendAppUpdatePushes(ADMIN_ID, request)

        assertEquals(setOf("token-1"), messagesByToken.captured.keys)
        assertEquals(
            AppUpdatePushResponse(
                targetCount = 2,
                sentCount = 1,
                successCount = 1,
                invalidCount = 0,
                retryableCount = 0,
            ),
            response,
        )
    }

    @Test
    fun `죽은 토큰은 지우고 일시 장애로 못 받은 사용자는 중복 방지 표시를 지운다`() {
        givenPage(cursor = 0, listOf(createTarget(1), createTarget(2), createTarget(3)), hasNext = false)
        every { notificationService.sendPushes(any()) } returns
            PushResult(successCount = 1, invalidTokens = listOf("token-2"), retryableTokens = listOf("token-3"))

        val response = createService().sendAppUpdatePushes(ADMIN_ID, request)

        verify { notificationService.deleteInvalidDeviceTokens(listOf("token-2")) }
        verify {
            pushDeduplicationRepository.deleteSentMarks(
                listOf(NotificationId.createAppUpdateId(3, DevicePlatform.ANDROID, "1.4.0")),
            )
        }
        assertEquals(1, response.invalidCount)
        assertEquals(1, response.retryableCount)
    }

    @Test
    fun `대상이 없으면 건수가 모두 0이다`() {
        givenPage(cursor = 0, emptyList(), hasNext = false)

        val response = createService().sendAppUpdatePushes(ADMIN_ID, request)

        assertEquals(AppUpdatePushResponse.EMPTY, response)
        verify(exactly = 0) { notificationService.sendPushes(any()) }
    }

    @Test
    fun `21시부터는 보내지 않는다`() {
        assertBusinessException(AdminErrorCode.PUSH_NOT_ALLOWED_TIME) {
            createService(now = "2026-11-22T21:00:00+09:00").sendAppUpdatePushes(ADMIN_ID, request)
        }
        verify(exactly = 0) { deviceTokenRepository.findAppUpdatePushTargetsAfterCursor(any(), any(), any()) }
    }

    @Test
    fun `8시 전에는 보내지 않는다`() {
        assertBusinessException(AdminErrorCode.PUSH_NOT_ALLOWED_TIME) {
            createService(now = "2026-11-22T07:59:00+09:00").sendAppUpdatePushes(ADMIN_ID, request)
        }
        verify(exactly = 0) { notificationService.sendPushes(any()) }
    }

    companion object {
        private const val ADMIN_ID = 1L
        private const val PAGE_SIZE = 500
        private const val PLAY_STORE_LINK = "https://play.google.com/store/apps/details?id=com.dandi.nyummy"
        private const val APP_STORE_LINK = "https://apps.apple.com/kr/app/id6836862137"
    }
}
