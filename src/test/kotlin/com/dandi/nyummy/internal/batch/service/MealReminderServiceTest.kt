package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushResult
import com.dandi.nyummy.internal.batch.dto.MealReminderTargetResult
import com.dandi.nyummy.internal.batch.repository.PushDeduplicationRepository
import com.dandi.nyummy.language.createTestMessageSource
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.enum.MealTime
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.user.repository.ProfileRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice
import org.springframework.data.domain.SliceImpl
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 식사 리마인더의 묶음 발송과 문구 언어를 다룬다. 2026-11-02 12시(KST)에 점심 리마인더를 보낸다.
 */
class MealReminderServiceTest {

    private val profileRepository = mockk<ProfileRepository>()
    private val mealRepository = mockk<MealRepository> {
        every { findUserIdsWithMealAfter(any(), any(), any()) } returns emptyList()
    }
    private val pushDeduplicationRepository = mockk<PushDeduplicationRepository> {
        every { createSentMark(any(), any()) } returns true
        every { deleteSentMarks(any()) } just Runs
    }
    private val notificationService = mockk<NotificationService> {
        every { sendPushes(any()) } answers
            { PushResult(firstArg<Map<String, PushMessage>>().size, emptyList(), emptyList()) }
        every { deleteInvalidDeviceTokens(any()) } just Runs
    }
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

    private fun createTarget(userId: Long, token: String = "token-$userId", language: Language? = null) =
        MealReminderTargetResult(
            userId = userId,
            catName = "냐미",
            breakfastHour = 8,
            lunchHour = LUNCH_HOUR,
            dinnerHour = 19,
            token = token,
            language = language,
        )

    private fun givenPage(cursor: Long, targets: List<MealReminderTargetResult>, hasNext: Boolean) {
        val page: Slice<MealReminderTargetResult> = SliceImpl(targets, PageRequest.of(0, PAGE_SIZE), hasNext)
        every { profileRepository.findMealReminderTargetsAfterCursor(LUNCH_HOUR, cursor, any()) } returns page
    }

    private fun givenUserIdPage(cursor: Long, userIds: List<Long>, hasNext: Boolean) =
        givenPage(cursor, userIds.map { createTarget(it) }, hasNext)

    private fun sendLunchReminders(vararg targets: MealReminderTargetResult): Map<String, PushMessage> {
        givenPage(cursor = 0, targets.toList(), hasNext = false)
        val messagesByToken = slot<Map<String, PushMessage>>()
        every { notificationService.sendPushes(capture(messagesByToken)) } returns
            PushResult(targets.size, emptyList(), emptyList())

        mealReminderService.sendMealReminders()

        return messagesByToken.captured
    }

    @Test
    fun `다음 묶음이 있으면 직전 묶음의 마지막 userId를 커서로 다음 묶음을 조회한다`() {
        givenUserIdPage(cursor = 0, listOf(1, 2), hasNext = true)
        givenUserIdPage(cursor = 2, listOf(3), hasNext = false)

        mealReminderService.sendMealReminders(LUNCH_HOUR)

        verifyOrder {
            profileRepository.findMealReminderTargetsAfterCursor(LUNCH_HOUR, 0, PageRequest.of(0, PAGE_SIZE))
            profileRepository.findMealReminderTargetsAfterCursor(LUNCH_HOUR, 2, PageRequest.of(0, PAGE_SIZE))
        }
        verify(exactly = 2) { notificationService.sendPushes(any()) }
    }

    @Test
    fun `다음 묶음이 없으면 더 조회하지 않는다`() {
        givenUserIdPage(cursor = 0, listOf(1, 2), hasNext = false)

        mealReminderService.sendMealReminders(LUNCH_HOUR)

        verify(exactly = 1) { profileRepository.findMealReminderTargetsAfterCursor(any(), any(), any()) }
    }

    @Test
    fun `최근 기록으로 걸러진 사용자가 묶음의 마지막이어도 그 userId를 커서로 다음 묶음을 조회한다`() {
        givenUserIdPage(cursor = 0, listOf(1, 2), hasNext = true)
        givenUserIdPage(cursor = 2, listOf(3), hasNext = false)
        every {
            mealRepository.findUserIdsWithMealAfter(listOf(1L, 2L), MealStatus.FAILED, any())
        } returns listOf(2L)

        mealReminderService.sendMealReminders(LUNCH_HOUR)

        verify(exactly = 1) { profileRepository.findMealReminderTargetsAfterCursor(LUNCH_HOUR, 2, any()) }
        verify { notificationService.sendPushes(match { it.keys == setOf("token-1") }) }
    }

    @Test
    fun `최근 한 시간 안에 실패하지 않은 식사가 있는 사용자에게는 보내지 않는다`() {
        // 지금은 12:00 KST(03:00Z)이고 한 시간 전은 02:00Z다. 실패한 식사만 빼고 본다.
        val oneHourAgo = Instant.parse("2026-11-02T02:00:00Z")
        every { mealRepository.findUserIdsWithMealAfter(listOf(1L, 2L), MealStatus.FAILED, oneHourAgo) } returns
            listOf(1L)

        val messagesByToken = sendLunchReminders(
            createTarget(1L, "recorded-token", Language.KO),
            createTarget(2L, "not-recorded-token", Language.KO),
        )

        assertEquals(setOf("not-recorded-token"), messagesByToken.keys)
    }

    @Test
    fun `최근 기록은 묶음마다 한 번에 1시간 전 이후 실패하지 않은 식사로 확인한다`() {
        givenUserIdPage(cursor = 0, listOf(1, 2), hasNext = true)
        givenUserIdPage(cursor = 2, listOf(3), hasNext = false)

        mealReminderService.sendMealReminders(LUNCH_HOUR)

        val oneHourAgo = Instant.parse("2026-11-02T11:00:00+09:00")
        verifyOrder {
            mealRepository.findUserIdsWithMealAfter(listOf(1L, 2L), MealStatus.FAILED, oneHourAgo)
            mealRepository.findUserIdsWithMealAfter(listOf(3L), MealStatus.FAILED, oneHourAgo)
        }
        verify(exactly = 2) { mealRepository.findUserIdsWithMealAfter(any(), any(), any()) }
    }

    @Test
    fun `무효 토큰 삭제와 재시도 표시 삭제를 묶음마다 한다`() {
        givenUserIdPage(cursor = 0, listOf(1, 2), hasNext = true)
        givenUserIdPage(cursor = 2, listOf(3), hasNext = false)
        every { notificationService.sendPushes(match { "token-1" in it }) } returns
            PushResult(successCount = 1, invalidTokens = listOf("token-1"), retryableTokens = emptyList())
        every { notificationService.sendPushes(match { "token-3" in it }) } returns
            PushResult(successCount = 0, invalidTokens = emptyList(), retryableTokens = listOf("token-3"))

        mealReminderService.sendMealReminders(LUNCH_HOUR)

        verifyOrder {
            notificationService.deleteInvalidDeviceTokens(listOf("token-1"))
            notificationService.deleteInvalidDeviceTokens(emptyList())
        }
        verify {
            pushDeduplicationRepository.deleteSentMarks(
                listOf(NotificationId.createMealReminderId(3, TODAY, MealTime.LUNCH)),
            )
        }
    }

    @Test
    fun `이미 발송 표시가 있는 사용자는 보내지 않는다`() {
        every {
            pushDeduplicationRepository.createSentMark(
                NotificationId.createMealReminderId(1, TODAY, MealTime.LUNCH),
                any(),
            )
        } returns false

        val messagesByToken = sendLunchReminders(createTarget(1), createTarget(2))

        assertEquals(setOf("token-2"), messagesByToken.keys)
    }

    @Test
    fun `대상이 없으면 발송하지 않는다`() {
        givenUserIdPage(cursor = 0, emptyList<Long>(), hasNext = false)

        mealReminderService.sendMealReminders(LUNCH_HOUR)

        verify(exactly = 0) { notificationService.sendPushes(any()) }
    }

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
        const val PAGE_SIZE = 500
        val TODAY: LocalDate = LocalDate.of(2026, 11, 2)
    }
}
