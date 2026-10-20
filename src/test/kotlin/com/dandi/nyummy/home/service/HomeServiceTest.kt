package com.dandi.nyummy.home.service

import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.inquiry.service.InquiryService
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.meal.dto.StreakResponse
import com.dandi.nyummy.meal.dto.TodayMealSummaryResponse
import com.dandi.nyummy.meal.service.MealService
import com.dandi.nyummy.room.service.RoomService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeServiceTest {

    private val userId = 1L

    private val mealService = mockk<MealService>()
    private val inquiryService = mockk<InquiryService>()
    private val mailService = mockk<MailService>()
    private val roomService = mockk<RoomService>()
    private val coinService = mockk<CoinService>()

    private val homeService = HomeService(
        mealService = mealService,
        inquiryService = inquiryService,
        mailService = mailService,
        roomService = roomService,
        coinService = coinService,
    )

    init {
        every { coinService.getBalance(userId) } returns 0
        every { mealService.getStreak(userId) } returns StreakResponse(streakDays = 0, recordsUntilNextReward = 0)
        every { mealService.getTodayMealSummary(userId) } returns TodayMealSummaryResponse(
            todayRecordedCount = 0,
            todayAttemptCount = 0,
            todayMaxAttemptCount = 5,
            todayCurrentCalory = 0,
            todayTargetCalory = 2000,
        )
        every { roomService.getEquippedRoomItems(userId) } returns emptyList()
    }

    private fun givenBadgeSources(existsNotClaimedMail: Boolean, existsUnreadAnswer: Boolean) {
        every { mailService.existsNotClaimedMail(userId) } returns existsNotClaimedMail
        every { inquiryService.existsUnreadAnswer(userId) } returns existsUnreadAnswer
    }

    // getHome — hasMailboxBadge

    @Test
    fun `받을 수 있는 우편이 있으면 배지를 켜고 문의 답변은 조회하지 않는다`() {
        givenBadgeSources(existsNotClaimedMail = true, existsUnreadAnswer = false)

        assertTrue(homeService.getHome(userId).hasMailboxBadge)
        verify(exactly = 0) { inquiryService.existsUnreadAnswer(any()) }
    }

    @Test
    fun `받을 수 있는 우편이 없어도 안 읽은 문의 답변이 있으면 배지를 켠다`() {
        givenBadgeSources(existsNotClaimedMail = false, existsUnreadAnswer = true)

        assertTrue(homeService.getHome(userId).hasMailboxBadge)
    }

    @Test
    fun `받을 수 있는 우편과 안 읽은 문의 답변이 모두 없으면 배지를 끈다`() {
        givenBadgeSources(existsNotClaimedMail = false, existsUnreadAnswer = false)

        assertFalse(homeService.getHome(userId).hasMailboxBadge)
    }
}
