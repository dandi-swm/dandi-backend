package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.mail.entity.Mail
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealRecognitionOutcome
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.RewardBoxResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealAnalysisJob
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealRecognitionCategory
import com.dandi.nyummy.meal.enum.MealRecognitionDeferralCategory
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
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.dao.DataAccessResourceFailureException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    private val mealFoodService = mockk<MealFoodService>()
    private val mailService = mockk<MailService>()
    private val streakService = mockk<StreakService>()

    private val mealTransactionService = MealTransactionService(
        profileRepository = profileRepository,
        mealAttemptService = mealAttemptService,
        mealRepository = mealRepository,
        mealAnalysisJobRepository = mealAnalysisJobRepository,
        mealFoodService = mealFoodService,
        mailService = mailService,
        streakService = streakService,
    )

    init {
        every { profileRepository.findProfileWithLockByUserId(userId) } returns Profile(userId = userId)
        every { mealAttemptService.validateDailyCount(userId) } just Runs
        every { mealRepository.save(meal) } returns meal
        every { mealAnalysisJobRepository.save(any()) } answers { firstArg() }
        every { mailService.createMealRewardMail(any(), any(), any(), any(), any()) } returns mockk<Mail>()
        every { streakService.updateStreak(userId) } just Runs
        every { mealFoodService.createMealFoods(any(), any(), any()) } returns emptyList()
        every { mealAnalysisJobRepository.updateAvailableAt(any(), any()) } returns 1
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    // createMeal

    @Test
    fun `프로필을 잠근 뒤 횟수를 세고 식사와 상자 우편을 저장하고 연속 기록을 갱신한다`() {
        mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt, Language.KO)

        verifyOrder {
            profileRepository.findProfileWithLockByUserId(userId)
            mealAttemptService.validateDailyCount(userId)
            mealRepository.save(meal)
            mailService.createMealRewardMail(
                userId,
                RewardBoxCategory.GOLD,
                412,
                rewardMailExpiresAt,
                Language.KO,
            )
            streakService.updateStreak(userId)
        }
    }

    @Test
    fun `보상 상자 우편은 식사를 기록한 요청의 언어로 만든다`() {
        mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt, Language.EN)

        verify(exactly = 1) {
            mailService.createMealRewardMail(
                userId,
                RewardBoxCategory.GOLD,
                412,
                rewardMailExpiresAt,
                Language.EN,
            )
        }
    }

    @Test
    fun `식사를 저장한 뒤 그 식사의 분석 작업을 같은 트랜잭션에 저장한다`() {
        mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt, Language.JA)

        verifyOrder {
            mealRepository.save(meal)
            mealAnalysisJobRepository.save(match { it.mealId == meal.id && it.language == Language.JA })
        }
    }

    @Test
    fun `다시 센 횟수가 상한이면 식사와 우편을 저장하지 않고 연속 기록도 갱신하지 않는다`() {
        every { mealAttemptService.validateDailyCount(userId) } throws
            BusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED)

        assertBusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED) {
            mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt, Language.KO)
        }
        verify(exactly = 0) { mealRepository.save(any()) }
        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
        verify(exactly = 0) { mailService.createMealRewardMail(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { streakService.updateStreak(any()) }
    }

    @Test
    fun `프로필이 없으면 PROFILE_NOT_FOUND이고 횟수를 세거나 저장하지 않는다`() {
        every { profileRepository.findProfileWithLockByUserId(userId) } returns null

        assertBusinessException(UserErrorCode.PROFILE_NOT_FOUND) {
            mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt, Language.KO)
        }
        verify(exactly = 0) { mealAttemptService.validateDailyCount(any()) }
        verify(exactly = 0) { mealRepository.save(any()) }
        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
    }

    @Test
    fun `우편 저장이 실패하면 예외를 그대로 던진다`() {
        // 예외가 밖으로 나가야 @Transactional이 먼저 저장한 식사까지 롤백한다.
        every { mailService.createMealRewardMail(any(), any(), any(), any(), any()) } throws
            DataAccessResourceFailureException("mail insert failed")

        assertFailsWith<DataAccessResourceFailureException> {
            mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt, Language.KO)
        }
    }

    @Test
    fun `연속 기록 갱신이 실패하면 예외를 그대로 던진다`() {
        // 예외가 밖으로 나가야 @Transactional이 먼저 저장한 식사와 우편까지 롤백한다.
        every { streakService.updateStreak(userId) } throws DataAccessResourceFailureException("streak insert failed")

        assertFailsWith<DataAccessResourceFailureException> {
            mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt, Language.KO)
        }
    }

    // createMealV2

    private val nutritionDelay = Duration.ofMinutes(5)
    private val recognition = MealRecognitionResult(
        inputCategory = MealInputCategory.FOOD_PHOTO,
        name = "불고기 백반",
        foods = listOf(
            MealFoodRecognitionResult(name = "불고기", portion = BigDecimal("1.5")),
            MealFoodRecognitionResult(name = "잡곡밥", portion = BigDecimal("1.0")),
        ),
        model = "gemini-recognition-test",
        promptVersion = "v1",
    )

    private fun createMealV2(outcome: MealRecognitionOutcome, language: Language = Language.KO) =
        mealTransactionService.createMealV2(meal, outcome, rewardBox, rewardMailExpiresAt, nutritionDelay, language)

    @Test
    fun `음식으로 인식했으면 요청 안에서 인식한 것으로 음식 줄을 저장하고 저장한 줄을 돌려준다`() {
        val savedMealFoods = listOf(
            MealFood(
                mealId = meal.id,
                mealFoodSnapshotId = 11L,
                displayOrder = 1,
                name = "불고기",
                portion = BigDecimal("1.5"),
            ),
        )
        every {
            mealFoodService.createMealFoods(meal.id, recognition, MealRecognitionCategory.SYNC)
        } returns savedMealFoods

        val mealFoods = createMealV2(MealRecognitionOutcome.Recognized(recognition))

        assertEquals(savedMealFoods, mealFoods)
        verify(exactly = 1) { mealFoodService.createMealFoods(meal.id, recognition, MealRecognitionCategory.SYNC) }
    }

    @Test
    fun `음식으로 인식했으면 영양 작업을 만들고 지연 시간 뒤부터 가져가게 미룬다`() {
        val job = slot<MealAnalysisJob>()
        every { mealAnalysisJobRepository.save(capture(job)) } answers { firstArg() }

        createMealV2(MealRecognitionOutcome.Recognized(recognition), Language.JA)

        assertEquals(MealAnalysisJobCategory.NUTRITION, job.captured.category)
        assertEquals(Language.JA, job.captured.language)
        verify(exactly = 1) { mealAnalysisJobRepository.updateAvailableAt(job.captured.id, 300) }
    }

    @Test
    fun `음식이 아니면 식사만 남기고 작업도 음식 줄도 만들지 않는다`() {
        val mealFoods = createMealV2(MealRecognitionOutcome.NotFood)

        assertTrue(mealFoods.isEmpty())
        verify(exactly = 1) { mealRepository.save(meal) }
        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
        verify(exactly = 0) { mealFoodService.createMealFoods(any(), any(), any()) }
    }

    @Test
    fun `요청 안에서 인식을 끝내지 못했으면 바로 가져갈 인식 작업만 만든다`() {
        val job = slot<MealAnalysisJob>()
        every { mealAnalysisJobRepository.save(capture(job)) } answers { firstArg() }

        val mealFoods = createMealV2(MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.TIMEOUT))

        assertTrue(mealFoods.isEmpty())
        assertEquals(MealAnalysisJobCategory.RECOGNITION, job.captured.category)
        verify(exactly = 0) { mealAnalysisJobRepository.updateAvailableAt(any(), any()) }
        verify(exactly = 0) { mealFoodService.createMealFoods(any(), any(), any()) }
    }

    @Test
    fun `인식 결과와 상관없이 보상 상자 우편을 만들고 연속 기록을 갱신한다`() {
        val outcomes = listOf(
            MealRecognitionOutcome.Recognized(recognition),
            MealRecognitionOutcome.NotFood,
            MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.SATURATED),
        )

        outcomes.forEach { createMealV2(it) }

        verify(exactly = 3) {
            mailService.createMealRewardMail(userId, RewardBoxCategory.GOLD, 412, rewardMailExpiresAt, Language.KO)
        }
        verify(exactly = 3) { streakService.updateStreak(userId) }
    }

    @Test
    fun `v2도 다시 센 횟수가 상한이면 아무것도 저장하지 않는다`() {
        every { mealAttemptService.validateDailyCount(userId) } throws
            BusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED)

        assertBusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED) {
            createMealV2(MealRecognitionOutcome.Recognized(recognition))
        }
        verify(exactly = 0) { mealRepository.save(any()) }
        verify(exactly = 0) { mealFoodService.createMealFoods(any(), any(), any()) }
        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
        verify(exactly = 0) { mailService.createMealRewardMail(any(), any(), any(), any(), any()) }
    }
}
