package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.image.dto.UploadedImageResult
import com.dandi.nyummy.image.enum.ImagePurpose
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.mail.entity.Mail
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.config.RewardBoxProperties
import com.dandi.nyummy.meal.config.TutorialRewardBoxProperties
import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.RewardBoxResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.repository.ProfileRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * createMeal의 검사·확정 순서와 mealAt 결정, createTutorialMeal의 완료 기록과 보상 상자 우편,
 * getMealStatus의 조회·소유권 검증을 다룬다.
 * 하루 정책 자체는 MealAttemptServiceTest가 맡는다.
 */
class MealServiceTest {

    private val now = Instant.parse("2026-10-10T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userId = 1L
    private val imageKey = "meals/$userId/2026/10/10/uuid.jpg"
    private val request = CreateMealRequest(imageKey = imageKey)
    private val rewardBoxes = mapOf(
        RewardBoxCategory.BRONZE to RewardBoxProperties(rate = 40, minCoinQuantity = 100, maxCoinQuantity = 199),
        RewardBoxCategory.SILVER to RewardBoxProperties(rate = 40, minCoinQuantity = 200, maxCoinQuantity = 349),
        RewardBoxCategory.GOLD to RewardBoxProperties(rate = 20, minCoinQuantity = 350, maxCoinQuantity = 500),
    )

    private val analysisService = mockk<AnalysisService>()
    private val mealRepository = mockk<MealRepository>()
    private val imageService = mockk<ImageService>()
    private val mealAttemptService = mockk<MealAttemptService>()
    private val profileRepository = mockk<ProfileRepository>()
    private val analysisFeedbackService = mockk<AnalysisFeedbackService>()
    private val mealTransactionService = mockk<MealTransactionService>()
    private val random = mockk<Random>()
    private val mailService = mockk<MailService>()

    private val mealService = MealService(
        analysisService = analysisService,
        mealRepository = mealRepository,
        imageService = imageService,
        mealAttemptService = mealAttemptService,
        clock = clock,
        profileRepository = profileRepository,
        mealProperties = MealProperties(
            maxDailyCount = 5,
            analysisFeedbackRate = 100,
            intakeTolerance = 0.2,
            minPositiveMacroCount = 2,
            rewardBoxes = rewardBoxes,
            rewardMailTimeToLive = Duration.ofDays(7),
            tutorialRewardBox = TutorialRewardBoxProperties(itemIds = listOf(2L, 3L), coinQuantity = 100),
        ),
        analysisFeedbackService = analysisFeedbackService,
        mealTransactionService = mealTransactionService,
        random = random,
        mailService = mailService,
    )

    private val savedMeal = slot<Meal>()

    init {
        every { mealRepository.existsByImageKey(imageKey) } returns false
        every { mealAttemptService.validateDailyCount(userId) } just Runs
        every { mealAttemptService.validateCapturedAt(userId, any()) } just Runs
        // 굴린 값 85는 골드(80~99), 골드 범위(350~500)에서 412코인을 뽑는다.
        every { random.nextInt(100) } returns 85
        every { random.nextInt(350, 501) } returns 412
        every { mealTransactionService.createMeal(capture(savedMeal), any(), any()) } answers { firstArg() }
        every { analysisService.analyzeNutrition(any()) } just Runs
    }

    /** ImageService가 공통 검증을 통과시킨 뒤 콜백을 실행하는 동작을 흉내 낸다. */
    private fun givenConfirmUpload(capturedAt: Instant?) {
        val validateAfterUpload = slot<(UploadedImageResult) -> Unit>()
        every { imageService.confirmUpload(userId, ImagePurpose.MEAL, imageKey, capture(validateAfterUpload)) } answers
            {
                val uploadedImage = UploadedImageResult(imageKey, capturedAt)
                validateAfterUpload.captured(uploadedImage)
                uploadedImage
            }
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    @Test
    fun `횟수 검사 후 식사 용도로 확정하고, EXIF 촬영 시각으로 식사를 저장해 분석한다`() {
        val capturedAt = now.minusSeconds(600)
        givenConfirmUpload(capturedAt)

        val result = mealService.createMeal(userId, request)

        assertEquals(capturedAt, savedMeal.captured.mealAt)
        assertEquals(imageKey, savedMeal.captured.imageKey)
        assertEquals(MealStatus.WAITING, result.status)
        verifyOrder {
            mealAttemptService.validateDailyCount(userId)
            imageService.confirmUpload(userId, ImagePurpose.MEAL, imageKey, any())
            mealTransactionService.createMeal(any(), any(), any())
            analysisService.analyzeNutrition(savedMeal.captured)
        }
    }

    @Test
    fun `식사를 기록하면 뽑은 상자와 만료 시각으로 식사와 우편을 함께 저장하고 응답에 등급을 담는다`() {
        givenConfirmUpload(now)

        val result = mealService.createMeal(userId, request)

        verify(exactly = 1) {
            mealTransactionService.createMeal(
                savedMeal.captured,
                RewardBoxResult(RewardBoxCategory.GOLD, 412),
                now.plus(Duration.ofDays(7)),
            )
        }
        assertEquals(RewardBoxCategory.GOLD, result.rewardBoxCategory)
    }

    @Test
    fun `확정 콜백으로 오늘 촬영 검증을 넘긴다`() {
        givenConfirmUpload(now)

        mealService.createMeal(userId, request)

        verify { mealAttemptService.validateCapturedAt(userId, UploadedImageResult(imageKey, now)) }
    }

    @Test
    fun `EXIF 촬영 시각이 없으면 서버 시각으로 저장한다`() {
        givenConfirmUpload(capturedAt = null)

        mealService.createMeal(userId, request)

        assertEquals(now, savedMeal.captured.mealAt)
    }

    @Test
    fun `이미 사용된 imageKey면 횟수 검사와 확정 없이 거부한다`() {
        every { mealRepository.existsByImageKey(imageKey) } returns true

        assertBusinessException(MealErrorCode.DUPLICATE_IMAGE_KEY) { mealService.createMeal(userId, request) }
        verify(exactly = 0) { mealAttemptService.validateDailyCount(any()) }
        verify(exactly = 0) { imageService.confirmUpload(any(), any(), any(), any()) }
        verify(exactly = 0) { mealTransactionService.createMeal(any(), any(), any()) }
    }

    @Test
    fun `하루 시도 횟수를 넘기면 이미지를 확정하지 않는다`() {
        // 확정 뒤에 거부하면 객체가 이미 status=committed라 라이프사이클이 정리하지 못한다.
        every { mealAttemptService.validateDailyCount(userId) } throws
            BusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED)

        assertBusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED) { mealService.createMeal(userId, request) }
        verify(exactly = 0) { imageService.confirmUpload(any(), any(), any(), any()) }
        verify(exactly = 0) { mealTransactionService.createMeal(any(), any(), any()) }
    }

    @Test
    fun `오늘 촬영 검증에서 거부되면 식사를 저장하지 않는다`() {
        givenConfirmUpload(now.minusSeconds(86_400))
        every { mealAttemptService.validateCapturedAt(userId, any()) } throws
            BusinessException(MealErrorCode.STALE_IMAGE)

        assertBusinessException(MealErrorCode.STALE_IMAGE) { mealService.createMeal(userId, request) }
        verify(exactly = 0) { mealTransactionService.createMeal(any(), any(), any()) }
        verify(exactly = 0) { analysisService.analyzeNutrition(any()) }
    }

    // createTutorialMeal

    private fun givenProfile(tutorialCompletedAt: Instant? = null): Profile {
        val profile = Profile(userId = userId).apply { this.tutorialCompletedAt = tutorialCompletedAt }
        every { profileRepository.findProfileWithLockByUserId(userId) } returns profile
        every { mailService.createRewardBoxMail(any(), any(), any(), any(), any(), any()) } returns mockk<Mail>()
        return profile
    }

    @Test
    fun `튜토리얼 식사를 만들면 완료 시각을 기록하고 골드 상자 우편을 만든다`() {
        val profile = givenProfile()

        mealService.createTutorialMeal(userId)

        assertEquals(now, profile.tutorialCompletedAt)
        verify(exactly = 1) {
            mailService.createRewardBoxMail(
                userId = userId,
                rewardBoxCategory = RewardBoxCategory.GOLD,
                coinQuantity = 100,
                content = "첫 기록 보상",
                expiresAt = null,
                itemIds = listOf(2L, 3L),
            )
        }
    }

    @Test
    fun `이미 튜토리얼을 완료했으면 tutorialAlreadyCompleted 예외를 던지고 우편을 만들지 않는다`() {
        val completedAt = now.minus(Duration.ofDays(1))
        val profile = givenProfile(tutorialCompletedAt = completedAt)

        assertBusinessException(MealErrorCode.TUTORIAL_ALREADY_COMPLETED) { mealService.createTutorialMeal(userId) }

        assertEquals(completedAt, profile.tutorialCompletedAt)
        verify(exactly = 0) { mailService.createRewardBoxMail(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `프로필이 없으면 PROFILE_NOT_FOUND 예외를 던진다`() {
        every { profileRepository.findProfileWithLockByUserId(userId) } returns null

        assertBusinessException(UserErrorCode.PROFILE_NOT_FOUND) { mealService.createTutorialMeal(userId) }

        verify(exactly = 0) { mailService.createRewardBoxMail(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `튜토리얼 식사는 식사 기록을 저장하지 않는다`() {
        givenProfile()

        mealService.createTutorialMeal(userId)

        verify(exactly = 0) { mealRepository.save(any()) }
        verify(exactly = 0) { mealTransactionService.createMeal(any(), any(), any()) }
    }

    // getMealStatus

    private val mealId = 10L

    @Test
    fun `분석 상태를 조회하면 식사 id와 상태를 돌려준다`() {
        val meal = Meal(status = MealStatus.ANALYZING, imageKey = imageKey, mealAt = now, userId = userId)
        every { mealRepository.findMealByIdAndDeletedAtIsNull(mealId) } returns meal

        val response = mealService.getMealStatus(userId, mealId)

        assertEquals(meal.id, response.id)
        assertEquals(MealStatus.ANALYZING, response.status)
    }

    @Test
    fun `식사가 없거나 삭제됐으면 MEAL_NOT_FOUND 예외를 던진다`() {
        every { mealRepository.findMealByIdAndDeletedAtIsNull(mealId) } returns null

        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) { mealService.getMealStatus(userId, mealId) }
    }

    @Test
    fun `다른 사용자의 식사면 FORBIDDEN 예외를 던진다`() {
        val otherUserId = 2L
        val meal = Meal(status = MealStatus.ANALYZING, imageKey = imageKey, mealAt = now, userId = otherUserId)
        every { mealRepository.findMealByIdAndDeletedAtIsNull(mealId) } returns meal

        assertBusinessException(AuthErrorCode.FORBIDDEN) { mealService.getMealStatus(userId, mealId) }
    }
}
