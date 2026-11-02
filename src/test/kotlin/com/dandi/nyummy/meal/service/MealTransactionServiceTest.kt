package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.mail.entity.Mail
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.meal.dto.RewardBoxResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.streak.service.StreakService
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.repository.ProfileRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MealTransactionServiceTest {

    private val userId = 1L
    private val now = Instant.parse("2026-10-19T03:00:00Z")
    private val rewardMailExpiresAt = now.plus(Duration.ofDays(7))
    private val rewardBox = RewardBoxResult(RewardBoxCategory.GOLD, 412)
    private val meal =
        Meal(status = MealStatus.WAITING, imageKey = "meals/$userId/uuid.jpg", mealAt = now, userId = userId)

    private val profileRepository = mockk<ProfileRepository>()
    private val mealAttemptService = mockk<MealAttemptService>()
    private val mealRepository = mockk<MealRepository>()
    private val mealAnalysisJobRepository = mockk<MealAnalysisJobRepository>()
    private val mailService = mockk<MailService>()
    private val streakService = mockk<StreakService>()

    private val mealTransactionService = MealTransactionService(
        profileRepository = profileRepository,
        mealAttemptService = mealAttemptService,
        mealRepository = mealRepository,
        mealAnalysisJobRepository = mealAnalysisJobRepository,
        mailService = mailService,
        streakService = streakService,
    )

    init {
        every { profileRepository.findProfileWithLockByUserId(userId) } returns Profile(userId = userId)
        every { mealAttemptService.validateDailyCount(userId) } just Runs
        every { mealRepository.save(meal) } returns meal
        every { mealAnalysisJobRepository.save(any()) } answers { firstArg() }
        every { mailService.createRewardBoxMail(any(), any(), any(), any(), any()) } returns mockk<Mail>()
        every { streakService.updateStreak(userId) } just Runs
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    // createMeal

    @Test
    fun `프로필을 잠근 뒤 횟수를 세고 식사와 상자 우편을 저장하고 연속 기록을 갱신한다`() {
        mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt)

        verifyOrder {
            profileRepository.findProfileWithLockByUserId(userId)
            mealAttemptService.validateDailyCount(userId)
            mealRepository.save(meal)
            mailService.createRewardBoxMail(userId, RewardBoxCategory.GOLD, 412, "식사 기록 보상", rewardMailExpiresAt)
            streakService.updateStreak(userId)
        }
    }

    @Test
    fun `식사를 저장한 뒤 그 식사의 분석 작업을 같은 트랜잭션에 저장한다`() {
        mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt)

        verifyOrder {
            mealRepository.save(meal)
            mealAnalysisJobRepository.save(match { it.mealId == meal.id })
        }
    }

    @Test
    fun `다시 센 횟수가 상한이면 식사와 우편을 저장하지 않고 연속 기록도 갱신하지 않는다`() {
        every { mealAttemptService.validateDailyCount(userId) } throws
            BusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED)

        assertBusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED) {
            mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt)
        }
        verify(exactly = 0) { mealRepository.save(any()) }
        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
        verify(exactly = 0) { mailService.createRewardBoxMail(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { streakService.updateStreak(any()) }
    }

    @Test
    fun `프로필이 없으면 PROFILE_NOT_FOUND이고 횟수를 세거나 저장하지 않는다`() {
        every { profileRepository.findProfileWithLockByUserId(userId) } returns null

        assertBusinessException(UserErrorCode.PROFILE_NOT_FOUND) {
            mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt)
        }
        verify(exactly = 0) { mealAttemptService.validateDailyCount(any()) }
        verify(exactly = 0) { mealRepository.save(any()) }
        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
    }

    @Test
    fun `우편 저장이 실패하면 예외를 그대로 던진다`() {
        // 예외가 밖으로 나가야 @Transactional이 먼저 저장한 식사까지 롤백한다.
        every { mailService.createRewardBoxMail(any(), any(), any(), any(), any()) } throws
            DataAccessResourceFailureException("mail insert failed")

        assertFailsWith<DataAccessResourceFailureException> {
            mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt)
        }
    }

    @Test
    fun `연속 기록 갱신이 실패하면 예외를 그대로 던진다`() {
        // 예외가 밖으로 나가야 @Transactional이 먼저 저장한 식사와 우편까지 롤백한다.
        every { streakService.updateStreak(userId) } throws DataAccessResourceFailureException("streak insert failed")

        assertFailsWith<DataAccessResourceFailureException> {
            mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt)
        }
    }
}
