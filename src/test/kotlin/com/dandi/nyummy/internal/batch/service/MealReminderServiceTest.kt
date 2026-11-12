package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushResult
import com.dandi.nyummy.internal.batch.dto.MealReminderTargetResult
import com.dandi.nyummy.internal.batch.repository.PushDeduplicationRepository
import com.dandi.nyummy.language.createTestMessageSource
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.user.repository.ProfileRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 식사 리마인더 문구를 기기 언어로 고르는지 다룬다. 2026-11-02 12시(KST)에 점심 리마인더를 보낸다.
 */
class MealReminderServiceTest {

    private val profileRepository = mockk<ProfileRepository>()
    private val mealRepository = mockk<MealRepository>()
    private val pushDeduplicationRepository = mockk<PushDeduplicationRepository>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val messageSource = createTestMessageSource()

    private val mealReminderService = MealReminderService(
        profileRepository = profileRepository,
        mealRepository = mealRepository,
        pushDeduplicationRepository = pushDeduplicationRepository,
        notificationService = notificationService,
        clock = Clock.fixed(Instant.parse("2026-11-02T12:00:00+09:00"), ZoneOffset.UTC),
        appLinkProperties = mockk<AppLinkProperties>(relaxed = true),
        messageSource = messageSource,
    )

    private fun sendLunchReminders(vararg targets: MealReminderTargetResult): Map<String, PushMessage> {
        every { profileRepository.findMealReminderTargets(LUNCH_HOUR) } returns targets.toList()
        every { mealRepository.existsCompletedMealAfter(any(), any(), any()) } returns false
        every { pushDeduplicationRepository.createSentMark(any(), any()) } returns true
        val messagesByToken = slot<Map<String, PushMessage>>()
        every { notificationService.sendPushes(capture(messagesByToken)) } returns PushResult(
            targets.size,
            emptyList(),
            emptyList(),
        )

        mealReminderService.sendMealReminders()

        return messagesByToken.captured
    }

    private fun createTarget(userId: Long, token: String, language: Language?) = MealReminderTargetResult(
        userId = userId,
        catName = "냐미",
        breakfastHour = 8,
        lunchHour = LUNCH_HOUR,
        dinnerHour = 19,
        token = token,
        language = language,
    )

    @Test
    fun `언어를 모르는 기기는 원본 언어로 지금까지와 같은 문구를 보낸다`() {
        val message = sendLunchReminders(createTarget(1L, "token", language = null)).getValue("token")

        assertEquals("집사, 점심은 먹었어? 🐾", message.title)
        assertEquals("냐미 너무 배고프다냥. 사진 한 장만 찍어줘!", message.body)
    }

    @Test
    fun `기기 언어가 있으면 끼니 라벨까지 그 언어로 보낸다`() {
        val message = sendLunchReminders(createTarget(1L, "token", Language.JA)).getValue("token")

        val lunchLabel = messageSource.getMessage("mealTime.LUNCH", Language.JA)
        assertEquals(messageSource.getMessage("push.mealReminder.title", Language.JA, lunchLabel), message.title)
        assertEquals(messageSource.getMessage("push.mealReminder.body", Language.JA, "냐미"), message.body)
    }

    @Test
    fun `같은 시각의 대상이라도 기기마다 자기 언어로 보낸다`() {
        val messagesByToken = sendLunchReminders(
            createTarget(1L, "ko-token", Language.KO),
            createTarget(2L, "en-token", Language.EN),
        )

        assertEquals("냐미 너무 배고프다냥. 사진 한 장만 찍어줘!", messagesByToken.getValue("ko-token").body)
        assertEquals(
            messageSource.getMessage("push.mealReminder.body", Language.EN, "냐미"),
            messagesByToken.getValue("en-token").body,
        )
    }

    private companion object {
        const val LUNCH_HOUR = 12
    }
}
