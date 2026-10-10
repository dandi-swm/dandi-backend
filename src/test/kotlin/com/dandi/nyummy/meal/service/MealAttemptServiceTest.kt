package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.image.dto.UploadedImage
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.repository.MealRepository
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MealAttemptServiceTest {

    // 2026-10-10 00:30 KST. UTC로는 아직 10월 9일이라, 서비스가 Clock의 zone이 아니라
    // 식사 기준 타임존(Asia/Seoul)으로 "오늘"을 계산하는지 드러난다.
    private val now = Instant.parse("2026-10-09T15:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userId = 1L

    // KST 하루 경계
    private val todayStart = Instant.parse("2026-10-09T15:00:00Z") // 10/10 00:00 KST
    private val tomorrowStart = Instant.parse("2026-10-10T15:00:00Z") // 10/11 00:00 KST

    private val mealRepository = mockk<MealRepository>()
    private val mealAttemptService = MealAttemptService(mealRepository, MealProperties(maxDailyCount = 5), clock)

    private fun givenTodayAttempts(count: Long) {
        every { mealRepository.countMealsByUserIdAndPeriod(userId, todayStart, tomorrowStart) } returns count
    }

    private fun assertStaleImage(block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(MealErrorCode.STALE_IMAGE, exception.errorCode)
    }

    // countDailyAttempts

    @Test
    fun `해당 날짜의 KST 0시부터 다음날 0시까지를 센다`() {
        givenTodayAttempts(3)

        assertEquals(3, mealAttemptService.countDailyAttempts(userId, LocalDate.of(2026, 10, 10)))
    }

    // validateDailyCount

    @Test
    fun `오늘 시도 횟수가 상한 미만이면 통과한다`() {
        givenTodayAttempts(4)

        mealAttemptService.validateDailyCount(userId)
    }

    @Test
    fun `오늘 시도 횟수가 상한에 닿으면 거부한다`() {
        givenTodayAttempts(5)

        val exception = assertFailsWith<BusinessException> { mealAttemptService.validateDailyCount(userId) }
        assertEquals(MealErrorCode.DAILY_COUNT_EXCEEDED, exception.errorCode)
    }

    // validateCapturedToday

    @Test
    fun `KST 기준 오늘 촬영한 사진이면 통과한다`() {
        // 10/10 00:05 KST
        val capturedAt = Instant.parse("2026-10-09T15:05:00Z")

        mealAttemptService.validateCapturedToday(userId, UploadedImage("key", capturedAt))
    }

    @Test
    fun `KST 기준 어제 촬영한 사진이면 거부한다`() {
        // 10/9 23:59 KST — 지금(00:30)과 31분 차이지만 날짜가 다르다
        val capturedAt = Instant.parse("2026-10-09T14:59:00Z")

        assertStaleImage { mealAttemptService.validateCapturedToday(userId, UploadedImage("key", capturedAt)) }
    }

    @Test
    fun `촬영 시각이 내일이면 거부한다`() {
        // 10/11 00:00 KST (기기 시계가 앞선 경우)
        assertStaleImage {
            mealAttemptService.validateCapturedToday(userId, UploadedImage("key", tomorrowStart))
        }
    }

    @Test
    fun `촬영 시각이 없으면 통과한다`() {
        mealAttemptService.validateCapturedToday(userId, UploadedImage("key", null))
    }
}
