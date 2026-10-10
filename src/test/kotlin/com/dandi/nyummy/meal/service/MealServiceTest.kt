package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.image.dto.UploadedImage
import com.dandi.nyummy.image.enum.ImagePurpose
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.user.repository.ProfileRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** createMeal의 검사·확정 순서와 mealAt 결정만 다룬다. 하루 정책 자체는 MealAttemptServiceTest가 맡는다. */
class MealServiceTest {

    private val now = Instant.parse("2026-10-10T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userId = 1L
    private val imageKey = "meals/$userId/2026/10/10/uuid.jpg"
    private val request = CreateMealRequest(imageKey = imageKey)

    private val analysisService = mockk<AnalysisService>()
    private val mealRepository = mockk<MealRepository>()
    private val imageService = mockk<ImageService>()
    private val mealAttemptService = mockk<MealAttemptService>()
    private val profileRepository = mockk<ProfileRepository>()

    private val mealService = MealService(
        analysisService = analysisService,
        mealRepository = mealRepository,
        imageService = imageService,
        mealAttemptService = mealAttemptService,
        clock = clock,
        profileRepository = profileRepository,
        mealProperties = MealProperties(maxDailyCount = 5),
    )

    private val savedMeal = slot<Meal>()

    init {
        every { mealRepository.existsByImageKey(imageKey) } returns false
        every { mealAttemptService.validateDailyCount(userId) } just Runs
        every { mealAttemptService.validateCapturedToday(userId, any()) } just Runs
        every { mealRepository.save(capture(savedMeal)) } answers { firstArg() }
        every { analysisService.analyzeNutrition(any()) } just Runs
    }

    /** ImageService가 공통 검증을 통과시킨 뒤 콜백을 실행하는 동작을 흉내 낸다. */
    private fun givenConfirmUpload(capturedAt: Instant?) {
        val validate = slot<(UploadedImage) -> Unit>()
        every { imageService.confirmUpload(userId, ImagePurpose.MEAL, imageKey, capture(validate)) } answers {
            val uploadedImage = UploadedImage(imageKey, capturedAt)
            validate.captured(uploadedImage)
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
        assertEquals(MealStatus.WAITING.name, result.status)
        verifyOrder {
            mealAttemptService.validateDailyCount(userId)
            imageService.confirmUpload(userId, ImagePurpose.MEAL, imageKey, any())
            mealRepository.save(any())
            analysisService.analyzeNutrition(savedMeal.captured)
        }
    }

    @Test
    fun `확정 콜백으로 오늘 촬영 검증을 넘긴다`() {
        givenConfirmUpload(now)

        mealService.createMeal(userId, request)

        verify { mealAttemptService.validateCapturedToday(userId, UploadedImage(imageKey, now)) }
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
    }

    @Test
    fun `하루 시도 횟수를 넘기면 이미지를 확정하지 않는다`() {
        // 확정 뒤에 거부하면 객체가 이미 status=committed라 라이프사이클이 정리하지 못한다.
        every { mealAttemptService.validateDailyCount(userId) } throws
            BusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED)

        assertBusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED) { mealService.createMeal(userId, request) }
        verify(exactly = 0) { imageService.confirmUpload(any(), any(), any(), any()) }
        verify(exactly = 0) { mealRepository.save(any()) }
    }

    @Test
    fun `오늘 촬영 검증에서 거부되면 식사를 저장하지 않는다`() {
        givenConfirmUpload(now.minusSeconds(86_400))
        every { mealAttemptService.validateCapturedToday(userId, any()) } throws
            BusinessException(MealErrorCode.STALE_IMAGE)

        assertBusinessException(MealErrorCode.STALE_IMAGE) { mealService.createMeal(userId, request) }
        verify(exactly = 0) { mealRepository.save(any()) }
        verify(exactly = 0) { analysisService.analyzeNutrition(any()) }
    }
}
