package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushResult
import com.dandi.nyummy.internal.batch.dto.RetentionTargetResult
import com.dandi.nyummy.internal.batch.repository.PushDeduplicationRepository
import com.dandi.nyummy.language.createTestMessageSource
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.notification.service.NotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 배치가 밀려도 밤에는 광고성 푸시가 나가지 않는지, 문구를 기기 언어로 고르는지 다룬다. 시각은 모두 KST다.
 */
class RetentionBatchServiceTest {

    private val catRepository = mockk<CatRepository>()
    private val pushDeduplicationRepository = mockk<PushDeduplicationRepository>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val appLinkProperties = mockk<AppLinkProperties>(relaxed = true)
    private val messageSource = createTestMessageSource()

    init {
        every { catRepository.findRetentionPushTargets() } returns emptyList()
        every { notificationService.sendPushes(any()) } returns PushResult(0, emptyList(), emptyList())
    }

    private fun createService(kstAt: String) = RetentionBatchService(
        catRepository = catRepository,
        pushDeduplicationRepository = pushDeduplicationRepository,
        notificationService = notificationService,
        clock = Clock.fixed(Instant.parse("$kstAt+09:00"), ZoneOffset.UTC),
        appLinkProperties = appLinkProperties,
        messageSource = messageSource,
    )

    // 2026-11-02 10시(KST)에 돌린다. 마지막 식사가 [daysAgo]일 전 같은 시각인 대상이다.
    private fun sendRetentionPush(daysAgo: Long, language: Language?): PushMessage {
        val lastMealAt = Instant.parse("2026-11-02T10:00:00+09:00").minus(daysAgo, ChronoUnit.DAYS)
        every { catRepository.findRetentionPushTargets() } returns listOf(
            RetentionTargetResult(
                userId = 1L,
                catName = "냐미",
                lastMealAt = lastMealAt,
                token = "token",
                language = language,
            ),
        )
        every { pushDeduplicationRepository.createSentMark(any(), any()) } returns true
        val messagesByToken = slot<Map<String, PushMessage>>()
        every { notificationService.sendPushes(capture(messagesByToken)) } returns
            PushResult(1, emptyList(), emptyList())

        createService("2026-11-02T10:00:00").sendDailyRetentionPushes()

        return messagesByToken.captured.getValue("token")
    }

    @Test
    fun `언어를 모르는 기기는 원본 언어로 지금까지와 같은 문구를 보낸다`() {
        val message = sendRetentionPush(daysAgo = 3, language = null)

        assertEquals("냐미 홀쭉해지고 있어요 🐾", message.title)
        assertEquals("3일째 밥을 못 먹었어요ㅠㅠ", message.body)
    }

    @Test
    fun `기기 언어가 있으면 그 언어 문구를 보낸다`() {
        val message = sendRetentionPush(daysAgo = 3, language = Language.JA)

        assertEquals(messageSource.getMessage("push.retention.loseWeight.title", Language.JA, "냐미"), message.title)
        assertEquals(messageSource.getMessage("push.retention.loseWeight.body", Language.JA, 3L), message.body)
    }

    @Test
    fun `14일째에는 잠든 고양이 문구를 기기 언어로 보낸다`() {
        val message = sendRetentionPush(daysAgo = 14, language = Language.EN)

        assertEquals(messageSource.getMessage("push.retention.sleep.title", Language.EN, "냐미"), message.title)
        assertEquals(messageSource.getMessage("push.retention.sleep.body", Language.EN), message.body)
    }

    @Test
    fun `전용 문구가 없는 날은 매일 문구를 기기 언어로 보낸다`() {
        val message = sendRetentionPush(daysAgo = 1, language = Language.JA)

        assertEquals(messageSource.getMessage("push.retention.daily.title", Language.JA, "냐미"), message.title)
        assertEquals(messageSource.getMessage("push.retention.daily.body", Language.JA), message.body)
    }

    @Test
    fun `오전 8시 정각에는 리텐션 푸시를 보낸다`() {
        createService("2026-11-02T08:00:00").sendDailyRetentionPushes()

        verify(exactly = 1) { notificationService.sendPushes(any()) }
    }

    @Test
    fun `20시 29분 59초에는 리텐션 푸시를 보낸다`() {
        createService("2026-11-02T20:29:59").sendDailyRetentionPushes()

        verify(exactly = 1) { notificationService.sendPushes(any()) }
    }

    @Test
    fun `20시 30분 정각에는 대상을 조회하지 않고 건너뛴다`() {
        createService("2026-11-02T20:30:00").sendDailyRetentionPushes()

        verify(exactly = 0) { catRepository.findRetentionPushTargets() }
        verify(exactly = 0) { notificationService.sendPushes(any()) }
    }

    @Test
    fun `오전 7시 59분 59초에는 대상을 조회하지 않고 건너뛴다`() {
        createService("2026-11-02T07:59:59").sendDailyRetentionPushes()

        verify(exactly = 0) { catRepository.findRetentionPushTargets() }
        verify(exactly = 0) { notificationService.sendPushes(any()) }
    }
}
