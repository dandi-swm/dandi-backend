package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.image.dto.UploadedImageContentResult
import com.dandi.nyummy.image.dto.UploadedImageResult
import com.dandi.nyummy.image.enum.ImagePurpose
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.mail.entity.Mail
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.config.MealRecognitionProperties
import com.dandi.nyummy.meal.config.RewardBoxProperties
import com.dandi.nyummy.meal.config.TutorialRewardBoxProperties
import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealFoodResponse
import com.dandi.nyummy.meal.dto.MealRecognitionOutcome
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.NutritionAnalysisResult
import com.dandi.nyummy.meal.dto.RewardBoxResult
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.UpdateMealNameRequest
import com.dandi.nyummy.meal.dto.UpdateMealNutritionRequest
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealAnalysisVersion
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealRecognitionDeferralCategory
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.mapper.toNutrition
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
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
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * createMeal의 검사·확정 순서와 mealAt 결정, createTutorialMeal의 완료 기록과 보상 상자 우편,
 * getMealStatus의 조회·소유권 검증, retryNutritionAnalysis의 재시도 가능 여부 검사,
 * updateMeal·deleteMeal이 식사를 잠가 읽는지와 검증,
 * updateMealNutrition이 보낸 항목만 바꾸는지와 수정 가능 상태 검사를 다룬다.
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

    private val mealRepository = mockk<MealRepository>()
    private val mealAnalysisJobRepository = mockk<MealAnalysisJobRepository>()
    private val imageService = mockk<ImageService>()
    private val mealAttemptService = mockk<MealAttemptService>()
    private val profileRepository = mockk<ProfileRepository>()
    private val analysisFeedbackService = mockk<AnalysisFeedbackService>()
    private val mealTransactionService = mockk<MealTransactionService>()
    private val mealRecognitionService = mockk<MealRecognitionService>()
    private val mealFoodService = mockk<MealFoodService>()
    private val random = mockk<Random>()
    private val mailService = mockk<MailService>()

    private val mealService = MealService(
        mealRepository = mealRepository,
        mealAnalysisJobRepository = mealAnalysisJobRepository,
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
            recognition = MealRecognitionProperties(
                responseTimeToLive = Duration.ofMillis(4500),
                reservedSaveTime = Duration.ofMillis(400),
                minRecognitionTime = Duration.ofSeconds(2),
                maxThreads = 30,
                nutritionDelay = Duration.ofMinutes(5),
            ),
        ),
        analysisFeedbackService = analysisFeedbackService,
        mealTransactionService = mealTransactionService,
        mealRecognitionService = mealRecognitionService,
        mealFoodService = mealFoodService,
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
        every { mealTransactionService.createMeal(capture(savedMeal), any(), any(), any()) } answers { firstArg() }
        every { mealAnalysisJobRepository.save(any()) } answers { firstArg() }
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
    fun `횟수 검사 후 식사 용도로 확정하고, EXIF 촬영 시각으로 식사를 저장한다`() {
        val capturedAt = now.minusSeconds(600)
        givenConfirmUpload(capturedAt)

        val result = mealService.createMeal(userId, request, Language.KO)

        assertEquals(capturedAt, savedMeal.captured.mealAt)
        assertEquals(imageKey, savedMeal.captured.imageKey)
        assertEquals(MealStatus.WAITING, result.status)
        verifyOrder {
            mealAttemptService.validateDailyCount(userId)
            imageService.confirmUpload(userId, ImagePurpose.MEAL, imageKey, any())
            mealTransactionService.createMeal(any(), any(), any(), any())
        }
    }

    @Test
    fun `식사를 기록해도 분석 작업 저장은 트랜잭션 서비스에 맡기고 WAITING을 돌려준다`() {
        givenConfirmUpload(now)

        val result = mealService.createMeal(userId, request, Language.KO)

        assertEquals(MealStatus.WAITING, result.status)
        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
    }

    @Test
    fun `식사를 기록하면 뽑은 상자와 만료 시각으로 식사와 우편을 함께 저장하고 응답에 등급을 담는다`() {
        givenConfirmUpload(now)

        val result = mealService.createMeal(userId, request, Language.KO)

        verify(exactly = 1) {
            mealTransactionService.createMeal(
                savedMeal.captured,
                RewardBoxResult(RewardBoxCategory.GOLD, 412),
                now.plus(Duration.ofDays(7)),
                Language.KO,
            )
        }
        assertEquals(RewardBoxCategory.GOLD, result.rewardBoxCategory)
    }

    @Test
    fun `확정 콜백으로 오늘 촬영 검증을 넘긴다`() {
        givenConfirmUpload(now)

        mealService.createMeal(userId, request, Language.KO)

        verify { mealAttemptService.validateCapturedAt(userId, UploadedImageResult(imageKey, now)) }
    }

    @Test
    fun `EXIF 촬영 시각이 없으면 서버 시각으로 저장한다`() {
        givenConfirmUpload(capturedAt = null)

        mealService.createMeal(userId, request, Language.KO)

        assertEquals(now, savedMeal.captured.mealAt)
    }

    @Test
    fun `이미 사용된 imageKey면 횟수 검사와 확정 없이 거부한다`() {
        every { mealRepository.existsByImageKey(imageKey) } returns true

        assertBusinessException(MealErrorCode.DUPLICATE_IMAGE_KEY) {
            mealService.createMeal(userId, request, Language.KO)
        }
        verify(exactly = 0) { mealAttemptService.validateDailyCount(any()) }
        verify(exactly = 0) { imageService.confirmUpload(any(), any(), any(), any()) }
        verify(exactly = 0) { mealTransactionService.createMeal(any(), any(), any(), any()) }
    }

    @Test
    fun `하루 시도 횟수를 넘기면 이미지를 확정하지 않는다`() {
        // 확정 뒤에 거부하면 객체가 이미 status=committed라 라이프사이클이 정리하지 못한다.
        every { mealAttemptService.validateDailyCount(userId) } throws
            BusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED)

        assertBusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED) {
            mealService.createMeal(userId, request, Language.KO)
        }
        verify(exactly = 0) { imageService.confirmUpload(any(), any(), any(), any()) }
        verify(exactly = 0) { mealTransactionService.createMeal(any(), any(), any(), any()) }
    }

    @Test
    fun `오늘 촬영 검증에서 거부되면 식사를 저장하지 않는다`() {
        givenConfirmUpload(now.minusSeconds(86_400))
        every { mealAttemptService.validateCapturedAt(userId, any()) } throws
            BusinessException(MealErrorCode.STALE_IMAGE)

        assertBusinessException(MealErrorCode.STALE_IMAGE) { mealService.createMeal(userId, request, Language.KO) }
        verify(exactly = 0) { mealTransactionService.createMeal(any(), any(), any(), any()) }
    }

    // createMealV2

    private val imageBytes = byteArrayOf(1, 2, 3)
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
    private val recognizedMealFoods = listOf(
        MealFood(mealId = 0L, mealFoodSnapshotId = 11L, displayOrder = 1, name = "불고기", portion = BigDecimal("1.5")),
        MealFood(mealId = 0L, mealFoodSnapshotId = 12L, displayOrder = 2, name = "잡곡밥", portion = BigDecimal("1.0")),
    )

    /** 이미지 확정, 인식 결과, 저장을 흉내 낸다. 저장은 음식으로 인식했을 때만 음식 줄을 돌려준다. */
    private fun givenMealV2(outcome: MealRecognitionOutcome, capturedAt: Instant? = now) {
        val validateAfterUpload = slot<(UploadedImageResult) -> Unit>()
        every {
            imageService.confirmUploadContent(userId, ImagePurpose.MEAL, imageKey, capture(validateAfterUpload))
        } answers {
            val uploadedImage = UploadedImageResult(imageKey, capturedAt)
            validateAfterUpload.captured(uploadedImage)
            UploadedImageContentResult(uploadedImage, imageBytes, "image/jpeg")
        }
        every { mealRecognitionService.recognizeMeal(any(), any(), any(), any()) } returns outcome
        every {
            mealTransactionService.createMealV2(capture(savedMeal), any(), any(), any(), any(), any())
        } returns if (outcome is MealRecognitionOutcome.Recognized) recognizedMealFoods else emptyList()
    }

    @Test
    fun `음식으로 인식하면 WAITING과 대표 이름과 계산 전 음식 줄을 응답한다`() {
        givenMealV2(MealRecognitionOutcome.Recognized(recognition))

        val response = mealService.createMealV2(userId, request, Language.KO)

        assertEquals(MealStatus.WAITING, response.status)
        assertEquals("불고기 백반", response.name)
        assertEquals(RewardBoxCategory.GOLD, response.rewardBoxCategory)
        val foods = assertNotNull(response.foods)
        assertEquals(listOf("불고기", "잡곡밥"), foods.map { it.name })
        assertEquals(listOf(BigDecimal("1.5"), BigDecimal("1.0")), foods.map { it.portion })
        // 영양과 아이콘은 Worker가 계산한 뒤에 생긴다.
        assertTrue(foods.all { it.nutrition == null && it.iconId == null && !it.isNutritionFixed })
    }

    @Test
    fun `음식으로 인식한 식사는 V2로, AI가 추천한 이름과 입력 유형으로 저장한다`() {
        givenMealV2(MealRecognitionOutcome.Recognized(recognition))

        mealService.createMealV2(userId, request, Language.KO)

        assertEquals(MealAnalysisVersion.V2, savedMeal.captured.analysisVersion)
        assertEquals(MealStatus.WAITING, savedMeal.captured.status)
        assertEquals("불고기 백반", savedMeal.captured.name)
        assertEquals(MealInputCategory.FOOD_PHOTO, savedMeal.captured.inputCategory)
        assertEquals(now, savedMeal.captured.mealAt)
    }

    @Test
    fun `음식이 아니면 FAILED이고 이름은 빈 문자열이며 음식 줄은 없다`() {
        givenMealV2(MealRecognitionOutcome.NotFood)

        val response = mealService.createMealV2(userId, request, Language.KO)

        assertEquals(MealStatus.FAILED, response.status)
        assertEquals("", response.name)
        assertNull(response.foods)
        assertEquals(MealInputCategory.NOT_FOOD, savedMeal.captured.inputCategory)
        // 음식이 아니어도 보상 상자는 준다.
        assertEquals(RewardBoxCategory.GOLD, response.rewardBoxCategory)
    }

    @Test
    fun `요청 안에서 인식을 끝내지 못하면 RECOGNIZING이고 이름과 음식 줄이 없다`() {
        givenMealV2(MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.TIMEOUT))

        val response = mealService.createMealV2(userId, request, Language.KO)

        assertEquals(MealStatus.RECOGNIZING, response.status)
        assertNull(response.name)
        assertNull(response.foods)
        assertNull(savedMeal.captured.inputCategory)
    }

    @Test
    fun `확정하며 받은 사진 내용과 요청을 받은 시각을 인식에 넘긴다`() {
        givenMealV2(MealRecognitionOutcome.Recognized(recognition))

        mealService.createMealV2(userId, request, Language.JA)

        verify(exactly = 1) { mealRecognitionService.recognizeMeal(imageBytes, "image/jpeg", Language.JA, now) }
    }

    @Test
    fun `인식 결과와 영양 작업 지연 설정과 요청 언어를 저장에 넘긴다`() {
        val outcome = MealRecognitionOutcome.Recognized(recognition)
        givenMealV2(outcome)

        mealService.createMealV2(userId, request, Language.EN)

        verify(exactly = 1) {
            mealTransactionService.createMealV2(
                meal = any(),
                outcome = outcome,
                rewardBox = RewardBoxResult(RewardBoxCategory.GOLD, 412),
                rewardMailExpiresAt = now.plus(Duration.ofDays(7)),
                nutritionDelay = Duration.ofMinutes(5),
                language = Language.EN,
            )
        }
    }

    @Test
    fun `v2도 EXIF 촬영 시각이 없으면 서버 시각으로 저장한다`() {
        givenMealV2(MealRecognitionOutcome.Recognized(recognition), capturedAt = null)

        mealService.createMealV2(userId, request, Language.KO)

        assertEquals(now, savedMeal.captured.mealAt)
    }

    @Test
    fun `v2도 이미 사용된 imageKey면 확정과 인식 없이 거부한다`() {
        every { mealRepository.existsByImageKey(imageKey) } returns true

        assertBusinessException(MealErrorCode.DUPLICATE_IMAGE_KEY) {
            mealService.createMealV2(userId, request, Language.KO)
        }
        verify(exactly = 0) { imageService.confirmUploadContent(any(), any(), any(), any()) }
        verify(exactly = 0) { mealRecognitionService.recognizeMeal(any(), any(), any(), any()) }
    }

    @Test
    fun `v2도 하루 시도 횟수를 넘기면 이미지를 확정하지 않고 인식도 하지 않는다`() {
        every { mealAttemptService.validateDailyCount(userId) } throws
            BusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED)

        assertBusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED) {
            mealService.createMealV2(userId, request, Language.KO)
        }
        verify(exactly = 0) { imageService.confirmUploadContent(any(), any(), any(), any()) }
        verify(exactly = 0) { mealRecognitionService.recognizeMeal(any(), any(), any(), any()) }
    }

    @Test
    fun `저장할 때 imageKey 유니크 제약에 걸리면 DUPLICATE_IMAGE_KEY로 거부한다`() {
        givenMealV2(MealRecognitionOutcome.Recognized(recognition))
        every { mealTransactionService.createMealV2(any(), any(), any(), any(), any(), any()) } throws
            DataIntegrityViolationException(
                "could not execute statement",
                RuntimeException("Duplicate entry 'meals/1/uuid.jpg' for key 'meals.uk_meals_image_key'"),
            )

        assertBusinessException(MealErrorCode.DUPLICATE_IMAGE_KEY) {
            mealService.createMealV2(userId, request, Language.KO)
        }
    }

    @Test
    fun `저장할 때 다른 제약에 걸리면 그 예외를 그대로 던진다`() {
        givenMealV2(MealRecognitionOutcome.Recognized(recognition))
        every { mealTransactionService.createMealV2(any(), any(), any(), any(), any(), any()) } throws
            DataIntegrityViolationException(
                "could not execute statement",
                RuntimeException("Duplicate entry '1' for key 'streaks.uk_streaks_user_id'"),
            )

        assertFailsWith<DataIntegrityViolationException> { mealService.createMealV2(userId, request, Language.KO) }
    }

    // createTutorialMeal

    private fun givenProfile(tutorialCompletedAt: Instant? = null): Profile {
        val profile = Profile(userId = userId).apply { this.tutorialCompletedAt = tutorialCompletedAt }
        every { profileRepository.findProfileWithLockByUserId(userId) } returns profile
        every { mailService.createTutorialRewardMail(any(), any(), any(), any(), any(), any()) } returns mockk<Mail>()
        return profile
    }

    @Test
    fun `튜토리얼 식사를 만들면 완료 시각을 기록하고 골드 상자 우편을 만든다`() {
        val profile = givenProfile()

        mealService.createTutorialMeal(userId, Language.KO)

        assertEquals(now, profile.tutorialCompletedAt)
        verify(exactly = 1) {
            mailService.createTutorialRewardMail(
                userId = userId,
                rewardBoxCategory = RewardBoxCategory.GOLD,
                coinQuantity = 100,
                language = Language.KO,
                expiresAt = null,
                itemIds = listOf(2L, 3L),
            )
        }
    }

    @Test
    fun `이미 튜토리얼을 완료했으면 tutorialAlreadyCompleted 예외를 던지고 우편을 만들지 않는다`() {
        val completedAt = now.minus(Duration.ofDays(1))
        val profile = givenProfile(tutorialCompletedAt = completedAt)

        assertBusinessException(MealErrorCode.TUTORIAL_ALREADY_COMPLETED) {
            mealService.createTutorialMeal(userId, Language.KO)
        }

        assertEquals(completedAt, profile.tutorialCompletedAt)
        verify(exactly = 0) { mailService.createTutorialRewardMail(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `프로필이 없으면 PROFILE_NOT_FOUND 예외를 던진다`() {
        every { profileRepository.findProfileWithLockByUserId(userId) } returns null

        assertBusinessException(UserErrorCode.PROFILE_NOT_FOUND) { mealService.createTutorialMeal(userId, Language.KO) }

        verify(exactly = 0) { mailService.createTutorialRewardMail(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `튜토리얼 식사는 식사 기록을 저장하지 않는다`() {
        givenProfile()

        mealService.createTutorialMeal(userId, Language.KO)

        verify(exactly = 0) { mealRepository.save(any()) }
        verify(exactly = 0) { mealTransactionService.createMeal(any(), any(), any(), any()) }
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

    // retryNutritionAnalysis

    @Test
    fun `FAILED인 식사를 재시도하면 작업을 다시 쌓고 WAITING을 돌려준다`() {
        val meal = Meal(status = MealStatus.FAILED, imageKey = imageKey, mealAt = now, userId = userId)
        every { mealRepository.findMealWithLockById(mealId) } returns meal

        val response = mealService.retryNutritionAnalysis(userId, mealId, Language.JA)

        verify(exactly = 1) {
            mealAnalysisJobRepository.save(match { it.mealId == meal.id && it.language == Language.JA })
        }
        assertEquals(MealStatus.WAITING, meal.status)
        assertEquals(meal.id, response.id)
        assertEquals(MealStatus.WAITING, response.status)
    }

    @Test
    fun `재시도할 식사가 없으면 MEAL_NOT_FOUND 예외를 던지고 작업을 쌓지 않는다`() {
        every { mealRepository.findMealWithLockById(mealId) } returns null

        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) {
            mealService.retryNutritionAnalysis(userId, mealId, Language.KO)
        }

        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
    }

    @Test
    fun `재시도할 식사가 지워졌으면 MEAL_NOT_FOUND 예외를 던지고 작업을 쌓지 않는다`() {
        val meal = Meal(status = MealStatus.FAILED, imageKey = imageKey, mealAt = now, userId = userId).apply {
            updateDeletedAt(now.minusSeconds(5))
        }
        every { mealRepository.findMealWithLockById(mealId) } returns meal

        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) {
            mealService.retryNutritionAnalysis(userId, mealId, Language.KO)
        }

        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
    }

    @Test
    fun `다른 사용자의 식사를 재시도하면 FORBIDDEN 예외를 던지고 작업을 쌓지 않는다`() {
        val otherUserId = 2L
        val meal = Meal(status = MealStatus.FAILED, imageKey = imageKey, mealAt = now, userId = otherUserId)
        every { mealRepository.findMealWithLockById(mealId) } returns meal

        assertBusinessException(AuthErrorCode.FORBIDDEN) {
            mealService.retryNutritionAnalysis(userId, mealId, Language.KO)
        }

        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
    }

    @Test
    fun `FAILED가 아닌 식사를 재시도하면 ANALYSIS_NOT_RETRYABLE 예외를 던지고 작업을 쌓지 않는다`() {
        val notRetryableStatuses = MealStatus.entries - MealStatus.FAILED

        notRetryableStatuses.forEach { status ->
            val meal = Meal(status = status, imageKey = imageKey, mealAt = now, userId = userId)
            every { mealRepository.findMealWithLockById(mealId) } returns meal

            assertBusinessException(MealErrorCode.ANALYSIS_NOT_RETRYABLE) {
                mealService.retryNutritionAnalysis(userId, mealId, Language.KO)
            }
        }

        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
    }

    // updateMeal

    @Test
    fun `이름을 바꾸면 잠가 읽은 식사의 이름을 바꾸고 응답에 담는다`() {
        val meal = Meal(status = MealStatus.COMPLETED, imageKey = imageKey, mealAt = now, userId = userId)
        every { mealRepository.findMealWithLockById(mealId) } returns meal
        every { imageService.createImageUrl(imageKey) } returns "https://image"
        every { analysisFeedbackService.canCreateAnalysisFeedback(meal) } returns false

        val response = mealService.updateMeal(userId, mealId, "비빔밥")

        verify(exactly = 1) { mealRepository.findMealWithLockById(mealId) }
        assertEquals("비빔밥", meal.name)
        assertEquals("비빔밥", response.name)
        assertEquals(MealStatus.COMPLETED, response.status)
    }

    @Test
    fun `이름을 바꿀 식사가 없으면 MEAL_NOT_FOUND 예외를 던진다`() {
        every { mealRepository.findMealWithLockById(mealId) } returns null

        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) { mealService.updateMeal(userId, mealId, "비빔밥") }
    }

    @Test
    fun `이름을 바꿀 식사가 지워졌으면 MEAL_NOT_FOUND 예외를 던지고 이름을 바꾸지 않는다`() {
        val meal = Meal(status = MealStatus.COMPLETED, imageKey = imageKey, mealAt = now, userId = userId).apply {
            updateName("김밥")
            updateDeletedAt(now.minusSeconds(5))
        }
        every { mealRepository.findMealWithLockById(mealId) } returns meal

        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) { mealService.updateMeal(userId, mealId, "비빔밥") }

        assertEquals("김밥", meal.name)
    }

    @Test
    fun `다른 사용자의 식사 이름을 바꾸면 FORBIDDEN 예외를 던지고 이름을 바꾸지 않는다`() {
        val otherUserId = 2L
        val meal = Meal(status = MealStatus.COMPLETED, imageKey = imageKey, mealAt = now, userId = otherUserId).apply {
            updateName("김밥")
        }
        every { mealRepository.findMealWithLockById(mealId) } returns meal

        assertBusinessException(AuthErrorCode.FORBIDDEN) { mealService.updateMeal(userId, mealId, "비빔밥") }

        assertEquals("김밥", meal.name)
    }

    // updateMealNutrition

    private val analyzedNutrition = Nutrition(calory = 520, carbs = 60, protein = 25, fat = 18)

    /** 분석이 완료돼 영양이 채워진 식사를 잠금 조회 결과로 준비한다. */
    private fun givenAnalyzedMeal(ownerId: Long = userId): Meal {
        val analysisResult = NutritionAnalysisResult(
            name = "김밥",
            nutrition = analyzedNutrition,
            catComment = "맛있겠다냥",
            iconId = 3L,
        )
        val meal = Meal(status = MealStatus.COMPLETED, imageKey = imageKey, mealAt = now, userId = ownerId).apply {
            updateAnalysisResult(analysisResult)
        }
        every { mealRepository.findMealWithLockById(mealId) } returns meal
        every { imageService.createImageUrl(imageKey) } returns "https://image"
        every { analysisFeedbackService.canCreateAnalysisFeedback(meal) } returns false
        return meal
    }

    @Test
    fun `영양 일부만 보내면 잠가 읽은 식사의 그 항목만 바꾸고 나머지는 유지한다`() {
        val meal = givenAnalyzedMeal()

        val response = mealService.updateMealNutrition(userId, mealId, UpdateMealNutritionRequest(carbs = 80))

        verify(exactly = 1) { mealRepository.findMealWithLockById(mealId) }
        val expected = analyzedNutrition.copy(carbs = 80)
        assertEquals(expected, meal.toNutrition())
        assertEquals(expected, response.nutrition)
    }

    @Test
    fun `영양 네 항목을 모두 보내면 모두 바꾼다`() {
        val meal = givenAnalyzedMeal()
        val request = UpdateMealNutritionRequest(calory = 700, carbs = 90, protein = 40, fat = 20)

        val response = mealService.updateMealNutrition(userId, mealId, request)

        val expected = Nutrition(calory = 700, carbs = 90, protein = 40, fat = 20)
        assertEquals(expected, meal.toNutrition())
        assertEquals(expected, response.nutrition)
    }

    @Test
    fun `영양을 0으로 보내면 유지하지 않고 0으로 바꾼다`() {
        val meal = givenAnalyzedMeal()

        mealService.updateMealNutrition(userId, mealId, UpdateMealNutritionRequest(fat = 0))

        assertEquals(analyzedNutrition.copy(fat = 0), meal.toNutrition())
    }

    @Test
    fun `바꿀 영양 항목이 없으면 영양을 그대로 두고 식사 정보를 돌려준다`() {
        val meal = givenAnalyzedMeal()

        val response = mealService.updateMealNutrition(userId, mealId, UpdateMealNutritionRequest())

        assertEquals(analyzedNutrition, meal.toNutrition())
        assertEquals(analyzedNutrition, response.nutrition)
    }

    @Test
    fun `영양을 바꿔도 이름과 아이콘과 고양이 한마디는 바꾸지 않는다`() {
        val meal = givenAnalyzedMeal()

        val response = mealService.updateMealNutrition(userId, mealId, UpdateMealNutritionRequest(calory = 700))

        assertEquals("김밥", response.name)
        assertEquals(3L, response.iconId)
        assertEquals("맛있겠다냥", response.catComment)
        assertEquals(MealStatus.COMPLETED, meal.status)
    }

    @Test
    fun `영양을 바꿀 식사가 없으면 MEAL_NOT_FOUND 예외를 던진다`() {
        every { mealRepository.findMealWithLockById(mealId) } returns null

        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) {
            mealService.updateMealNutrition(userId, mealId, UpdateMealNutritionRequest(calory = 700))
        }
    }

    @Test
    fun `영양을 바꿀 식사가 지워졌으면 MEAL_NOT_FOUND 예외를 던지고 영양을 바꾸지 않는다`() {
        val meal = givenAnalyzedMeal().apply { updateDeletedAt(now.minusSeconds(5)) }

        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) {
            mealService.updateMealNutrition(userId, mealId, UpdateMealNutritionRequest(calory = 700))
        }

        assertEquals(analyzedNutrition, meal.toNutrition())
    }

    @Test
    fun `다른 사용자의 식사 영양을 바꾸면 FORBIDDEN 예외를 던지고 영양을 바꾸지 않는다`() {
        val otherUserId = 2L
        val meal = givenAnalyzedMeal(ownerId = otherUserId)

        assertBusinessException(AuthErrorCode.FORBIDDEN) {
            mealService.updateMealNutrition(userId, mealId, UpdateMealNutritionRequest(calory = 700))
        }

        assertEquals(analyzedNutrition, meal.toNutrition())
    }

    @Test
    fun `COMPLETED가 아닌 식사의 영양을 바꾸면 ANALYSIS_NOT_COMPLETED 예외를 던지고 영양을 채우지 않는다`() {
        val notCompletedStatuses = MealStatus.entries - MealStatus.COMPLETED

        notCompletedStatuses.forEach { status ->
            val meal = Meal(status = status, imageKey = imageKey, mealAt = now, userId = userId)
            every { mealRepository.findMealWithLockById(mealId) } returns meal

            assertBusinessException(MealErrorCode.ANALYSIS_NOT_COMPLETED) {
                mealService.updateMealNutrition(userId, mealId, UpdateMealNutritionRequest(calory = 700))
            }

            assertNull(meal.calory)
            assertEquals(status, meal.status)
        }
    }

    // deleteMeal

    @Test
    fun `식사를 지우면 잠가 읽은 식사에 삭제 시각을 남긴다`() {
        val meal = Meal(status = MealStatus.ANALYZING, imageKey = imageKey, mealAt = now, userId = userId)
        every { mealRepository.findMealWithLockById(mealId) } returns meal

        mealService.deleteMeal(userId, mealId)

        verify(exactly = 1) { mealRepository.findMealWithLockById(mealId) }
        assertNotNull(meal.deletedAt)
    }

    @Test
    fun `지울 식사가 없으면 MEAL_NOT_FOUND 예외를 던진다`() {
        every { mealRepository.findMealWithLockById(mealId) } returns null

        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) { mealService.deleteMeal(userId, mealId) }
    }

    @Test
    fun `이미 지운 식사를 지우면 MEAL_NOT_FOUND 예외를 던지고 삭제 시각을 바꾸지 않는다`() {
        val deletedAt = now.minusSeconds(5)
        val meal = Meal(status = MealStatus.COMPLETED, imageKey = imageKey, mealAt = now, userId = userId).apply {
            updateDeletedAt(deletedAt)
        }
        every { mealRepository.findMealWithLockById(mealId) } returns meal

        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) { mealService.deleteMeal(userId, mealId) }

        assertEquals(deletedAt, meal.deletedAt)
    }

    @Test
    fun `다른 사용자의 식사를 지우면 FORBIDDEN 예외를 던지고 지우지 않는다`() {
        val otherUserId = 2L
        val meal = Meal(status = MealStatus.COMPLETED, imageKey = imageKey, mealAt = now, userId = otherUserId)
        every { mealRepository.findMealWithLockById(mealId) } returns meal

        assertBusinessException(AuthErrorCode.FORBIDDEN) { mealService.deleteMeal(userId, mealId) }

        assertNull(meal.deletedAt)
    }

    // getMealV2

    private fun createMealV2(status: MealStatus, ownerId: Long = userId) = Meal(
        status = status,
        imageKey = imageKey,
        mealAt = now,
        userId = ownerId,
        analysisVersion = MealAnalysisVersion.V2,
    )

    private fun givenMeal(meal: Meal, canCreateAnalysisFeedback: Boolean = false) {
        every { mealRepository.findMealByIdAndDeletedAtIsNull(mealId) } returns meal
        every { imageService.createImageUrl(imageKey) } returns "https://image"
        every { analysisFeedbackService.canCreateAnalysisFeedback(meal) } returns canCreateAnalysisFeedback
    }

    @Test
    fun `v2 단건은 계산이 끝난 식사의 합계와 코멘트, 음식 줄의 영양과 아이콘을 순서대로 준다`() {
        val meal = createMealV2(MealStatus.COMPLETED).apply {
            updateName("불고기 백반")
            updateNutrition(Nutrition(calory = 825, carbs = 88, protein = 44, fat = 31))
            updateIconId(12L)
            updateCatComment("든든하게 먹었다냥")
        }
        givenMeal(meal, canCreateAnalysisFeedback = true)
        every { mealFoodService.getMealFoods(meal.id) } returns listOf(
            MealFood(
                mealId = meal.id,
                mealFoodSnapshotId = 11L,
                displayOrder = 1,
                name = "불고기",
                portion = BigDecimal("1.5"),
            ).apply {
                updateServingNutrition(
                    ServingNutrition(BigDecimal("350"), BigDecimal("15.5"), BigDecimal("25.25"), BigDecimal("20")),
                )
                updateIconId(12L)
                isNutritionFixed = true
            },
            MealFood(
                mealId = meal.id,
                mealFoodSnapshotId = 12L,
                displayOrder = 2,
                name = "잡곡밥",
                portion = BigDecimal("1.0"),
            ).apply {
                updateServingNutrition(
                    ServingNutrition(BigDecimal("300"), BigDecimal("65"), BigDecimal("6"), BigDecimal("1")),
                )
                updateIconId(5L)
            },
        )

        val response = mealService.getMealV2(userId, mealId)

        assertEquals(MealAnalysisVersion.V2, response.analysisVersion)
        assertEquals("불고기 백반", response.name)
        assertEquals(MealStatus.COMPLETED, response.status)
        assertEquals(Nutrition(calory = 825, carbs = 88, protein = 44, fat = 31), response.nutrition)
        assertEquals(12L, response.iconId)
        assertEquals("든든하게 먹었다냥", response.catComment)
        assertEquals("https://image", response.imageUrl)
        assertEquals(true, response.canCreateAnalysisFeedback)
        assertEquals(
            listOf(
                // 350 × 1.5 = 525, 15.5 × 1.5 = 23.25 → 23, 25.25 × 1.5 = 37.875 → 38, 20 × 1.5 = 30
                MealFoodResponse(0L, "불고기", BigDecimal("1.5"), Nutrition(525, 23, 38, 30), true, 12L),
                MealFoodResponse(0L, "잡곡밥", BigDecimal("1.0"), Nutrition(300, 65, 6, 1), false, 5L),
            ),
            response.foods,
        )
    }

    @Test
    fun `v2 단건은 영양 계산을 기다리는 식사의 음식 줄을 영양과 아이콘 없이 준다`() {
        val meal = createMealV2(MealStatus.WAITING).apply { updateName("불고기 백반") }
        givenMeal(meal)
        every { mealFoodService.getMealFoods(meal.id) } returns recognizedMealFoods

        val response = mealService.getMealV2(userId, mealId)

        assertEquals(Nutrition.ZERO, response.nutrition)
        assertNull(response.iconId)
        assertNull(response.catComment)
        assertEquals(listOf("불고기", "잡곡밥"), response.foods?.map { it.name })
        assertTrue(response.foods.orEmpty().all { it.nutrition == null && it.iconId == null })
    }

    @Test
    fun `v2 단건은 음식 줄이 없는 식사면 foods가 null이다`() {
        // 아직 인식 중인 식사와 음식이 아닌 식사
        listOf(MealStatus.RECOGNIZING, MealStatus.FAILED).forEach { status ->
            val meal = createMealV2(status)
            givenMeal(meal)
            every { mealFoodService.getMealFoods(meal.id) } returns emptyList()

            assertNull(mealService.getMealV2(userId, mealId).foods)
        }
    }

    @Test
    fun `v2 단건은 V1 식사면 분석 버전이 V1이고 음식 줄을 조회하지 않으며 foods가 null이다`() {
        val meal = givenAnalyzedMeal()
        givenMeal(meal)

        val response = mealService.getMealV2(userId, mealId)

        assertEquals(MealAnalysisVersion.V1, response.analysisVersion)
        assertEquals("김밥", response.name)
        assertEquals(analyzedNutrition, response.nutrition)
        assertNull(response.foods)
        verify(exactly = 0) { mealFoodService.getMealFoods(any()) }
    }

    @Test
    fun `v2 단건도 식사가 없거나 삭제됐으면 MEAL_NOT_FOUND 예외를 던진다`() {
        every { mealRepository.findMealByIdAndDeletedAtIsNull(mealId) } returns null

        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) { mealService.getMealV2(userId, mealId) }
    }

    @Test
    fun `v2 단건도 다른 사용자의 식사면 FORBIDDEN 예외를 던지고 음식 줄을 조회하지 않는다`() {
        givenMeal(createMealV2(MealStatus.COMPLETED, ownerId = 2L))

        assertBusinessException(AuthErrorCode.FORBIDDEN) { mealService.getMealV2(userId, mealId) }
        verify(exactly = 0) { mealFoodService.getMealFoods(any()) }
    }

    // updateMealNameV2

    private fun givenLockedMeal(meal: Meal) {
        every { mealRepository.findMealWithLockById(mealId) } returns meal
        every { imageService.createImageUrl(imageKey) } returns "https://image"
        every { analysisFeedbackService.canCreateAnalysisFeedback(meal) } returns false
    }

    @Test
    fun `대표 이름을 바꾸면 잠가 읽은 식사의 이름만 바뀌고 음식 줄과 영양은 그대로인 단건을 돌려준다`() {
        val meal = createMealV2(MealStatus.COMPLETED).apply {
            updateName("불고기 백반")
            updateNutrition(Nutrition(calory = 825, carbs = 88, protein = 44, fat = 31))
        }
        givenLockedMeal(meal)
        every { mealFoodService.getMealFoods(meal.id) } returns recognizedMealFoods

        val response = mealService.updateMealNameV2(userId, mealId, UpdateMealNameRequest(name = "점심 불고기"))

        assertEquals("점심 불고기", meal.name)
        assertEquals("점심 불고기", response.name)
        assertEquals(MealStatus.COMPLETED, response.status)
        assertEquals(Nutrition(calory = 825, carbs = 88, protein = 44, fat = 31), response.nutrition)
        assertEquals(listOf("불고기", "잡곡밥"), response.foods?.map { it.name })
    }

    @Test
    fun `V1 식사의 대표 이름도 v2 경로로 바꿀 수 있고 응답의 분석 버전은 V1이다`() {
        val meal = givenAnalyzedMeal()

        val response = mealService.updateMealNameV2(userId, mealId, UpdateMealNameRequest(name = "참치김밥"))

        assertEquals("참치김밥", meal.name)
        assertEquals(MealAnalysisVersion.V1, response.analysisVersion)
        assertNull(response.foods)
    }

    @Test
    fun `대표 이름을 바꿀 식사가 없거나 삭제됐으면 MEAL_NOT_FOUND 예외를 던진다`() {
        every { mealRepository.findMealWithLockById(mealId) } returns null
        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) {
            mealService.updateMealNameV2(userId, mealId, UpdateMealNameRequest(name = "점심"))
        }

        val deletedMeal = createMealV2(MealStatus.COMPLETED).apply { updateDeletedAt(now) }
        every { mealRepository.findMealWithLockById(mealId) } returns deletedMeal
        assertBusinessException(MealErrorCode.MEAL_NOT_FOUND) {
            mealService.updateMealNameV2(userId, mealId, UpdateMealNameRequest(name = "점심"))
        }
    }

    @Test
    fun `다른 사용자의 식사면 대표 이름을 바꾸지 않고 FORBIDDEN 예외를 던진다`() {
        val meal = createMealV2(MealStatus.COMPLETED, ownerId = 2L).apply { updateName("불고기 백반") }
        givenLockedMeal(meal)

        assertBusinessException(AuthErrorCode.FORBIDDEN) {
            mealService.updateMealNameV2(userId, mealId, UpdateMealNameRequest(name = "점심"))
        }

        assertEquals("불고기 백반", meal.name)
    }

    // getDailyMeals·getDailyMealsV2

    // 서울 2026-10-10 하루는 [10-09 15:00Z, 10-10 15:00Z)다.
    private val dayStart = Instant.parse("2026-10-09T15:00:00Z")
    private val dayEnd = Instant.parse("2026-10-10T15:00:00Z")

    private fun givenDailyMeals(vararg meals: Meal) {
        every { mealRepository.findMealsByUserIdAndPeriod(userId, dayStart, dayEnd) } returns meals.toList()
        every { profileRepository.findProfileByUserId(userId) } returns null
    }

    @Test
    fun `v2 하루 목록은 식사마다 분석 버전을 주고 버전이 없는 식사는 V1이다`() {
        val v2Meal = createMealV2(MealStatus.COMPLETED).apply {
            updateName("불고기 백반")
            updateNutrition(Nutrition(calory = 825, carbs = 88, protein = 44, fat = 31))
            updateIconId(12L)
        }
        val v1Meal = givenAnalyzedMeal()
        givenDailyMeals(v2Meal, v1Meal)

        val response = mealService.getDailyMealsV2(userId, 2026, 10, 10)

        assertEquals(LocalDate.of(2026, 10, 10), response.date)
        assertEquals(listOf(MealAnalysisVersion.V2, MealAnalysisVersion.V1), response.meals.map { it.analysisVersion })
        assertEquals(listOf("불고기 백반", "김밥"), response.meals.map { it.name })
        assertEquals(listOf(825, 520), response.meals.map { it.calory })
        assertEquals(listOf<Long?>(12L, 3L), response.meals.map { it.iconId })
    }

    @Test
    fun `v2 하루 목록에서 계산이 끝나지 않은 식사는 영양이 0이고 합계에도 0으로 더해진다`() {
        val recognizingMeal = createMealV2(MealStatus.RECOGNIZING)
        givenDailyMeals(recognizingMeal, givenAnalyzedMeal())

        val response = mealService.getDailyMealsV2(userId, 2026, 10, 10)

        assertEquals(listOf(MealStatus.RECOGNIZING, MealStatus.COMPLETED), response.meals.map { it.status })
        assertEquals(0, response.meals.first().calory)
        assertNull(response.meals.first().iconId)
        assertEquals(analyzedNutrition, response.dailyNutrition.current)
        // 프로필이 비어 있으면 기본 권장량이다.
        assertEquals(Nutrition(calory = 2000, carbs = 250, protein = 100, fat = 70), response.dailyNutrition.target)
    }

    @Test
    fun `v1 하루 목록은 같은 하루를 조회해 같은 합계와 목표를 준다`() {
        givenDailyMeals(givenAnalyzedMeal())

        val response = mealService.getDailyMeals(userId, 2026, 10, 10)

        assertEquals(LocalDate.of(2026, 10, 10), response.date)
        assertEquals(listOf("김밥"), response.meals.map { it.name })
        assertEquals(analyzedNutrition, response.dailyNutrition.current)
        assertEquals(Nutrition(calory = 2000, carbs = 250, protein = 100, fat = 70), response.dailyNutrition.target)
    }
}
