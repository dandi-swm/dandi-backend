package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.push.dto.PushResult
import com.dandi.nyummy.internal.batch.repository.PushDeduplicationRepository
import com.dandi.nyummy.notification.service.NotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test

/**
 * 배치가 밀려도 밤에는 광고성 푸시가 나가지 않는지 다룬다. 시각은 모두 KST다.
 */
class RetentionBatchServiceTest {

    private val catRepository = mockk<CatRepository>()
    private val pushDeduplicationRepository = mockk<PushDeduplicationRepository>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val appLinkProperties = mockk<AppLinkProperties>(relaxed = true)

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
    )

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
