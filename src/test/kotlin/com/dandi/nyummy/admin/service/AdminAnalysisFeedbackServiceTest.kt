package com.dandi.nyummy.admin.service

import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.AnalysisFeedback
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.AnalysisFeedbackRepository
import com.dandi.nyummy.meal.repository.MealRepository
import io.mockk.every
import io.mockk.mockk
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.SliceImpl
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 어드민 분석 평가 목록 조회를 다룬다. 별점·이유 조건으로 거르는 쿼리 자체는 리포지토리가 맡는다.
 */
class AdminAnalysisFeedbackServiceTest {

    private val now = Instant.parse("2026-11-14T03:00:00Z")
    private val userId = 1L

    private val analysisFeedbackRepository = mockk<AnalysisFeedbackRepository>()
    private val mealRepository = mockk<MealRepository>()
    private val imageService = mockk<ImageService>()

    private val adminAnalysisFeedbackService = AdminAnalysisFeedbackService(
        analysisFeedbackRepository = analysisFeedbackRepository,
        mealRepository = mealRepository,
        imageService = imageService,
    )

    init {
        every { imageService.createImageUrl(any()) } answers { "https://image.example/${firstArg<String>()}" }
    }

    private fun analysisFeedback(id: Long, mealId: Long, rating: Float = 2.0f, reason: String? = "칼로리가 너무 높아요") =
        AnalysisFeedback(rating = rating, reason = reason, mealId = mealId, userId = userId).apply {
            // id는 DB가 채우는 val이라 테스트에서만 직접 넣는다.
            ReflectionTestUtils.setField(this, "id", id)
        }

    private fun meal(id: Long, deletedAt: Instant? = null) = Meal(
        status = MealStatus.COMPLETED,
        imageKey = "meals/$userId/$id.jpg",
        mealAt = now,
        userId = userId,
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
        name = "제육볶음"
        updateNutrition(Nutrition(calory = 726, carbs = 82, protein = 34, fat = 29))
        catComment = "단백질이 조금 아쉽다냥"
        deletedAt?.let { updateDeletedAt(it) }
    }

    @Test
    fun `다음 묶음이 있으면 마지막 평가 id를 다음 커서로 준다`() {
        every {
            analysisFeedbackRepository.findAnalysisFeedbacksBeforeCursor(
                null,
                null,
                Long.MAX_VALUE,
                PageRequest.of(0, 2),
            )
        } returns SliceImpl(
            listOf(analysisFeedback(id = 30, mealId = 300), analysisFeedback(id = 20, mealId = 200)),
            PageRequest.of(0, 2),
            true,
        )
        every { mealRepository.findAllById(listOf(300L, 200L)) } returns listOf(meal(id = 200), meal(id = 300))

        val response = adminAnalysisFeedbackService.getAnalysisFeedbacks(
            maxRating = null,
            hasReason = null,
            cursor = null,
            size = 2,
        )

        assertEquals(listOf(30L, 20L), response.analysisFeedbacks.map { it.analysisFeedbackId })
        assertEquals(20L, response.nextCursor)
        assertTrue(response.hasNext)
    }

    @Test
    fun `마지막 묶음이면 다음 커서가 없다`() {
        every {
            analysisFeedbackRepository.findAnalysisFeedbacksBeforeCursor(2.0f, true, 20L, PageRequest.of(0, 2))
        } returns SliceImpl(listOf(analysisFeedback(id = 10, mealId = 100)), PageRequest.of(0, 2), false)
        every { mealRepository.findAllById(listOf(100L)) } returns listOf(meal(id = 100))

        val response = adminAnalysisFeedbackService.getAnalysisFeedbacks(
            maxRating = 2.0f,
            hasReason = true,
            cursor = 20L,
            size = 2,
        )

        assertNull(response.nextCursor)
        assertFalse(response.hasNext)
    }

    @Test
    fun `평가마다 대상 식사의 지금 값과 이미지 URL을 붙인다`() {
        every {
            analysisFeedbackRepository.findAnalysisFeedbacksBeforeCursor(
                null,
                null,
                Long.MAX_VALUE,
                PageRequest.of(0, 20),
            )
        } returns SliceImpl(
            listOf(analysisFeedback(id = 10, mealId = 100, rating = 1.5f, reason = "양이 달라요")),
            PageRequest.of(0, 20),
            false,
        )
        every { mealRepository.findAllById(listOf(100L)) } returns listOf(meal(id = 100))

        val analysisFeedback = adminAnalysisFeedbackService.getAnalysisFeedbacks(null, null, null, 20)
            .analysisFeedbacks.single()

        assertEquals(1.5f, analysisFeedback.rating)
        assertEquals("양이 달라요", analysisFeedback.reason)
        assertEquals(100L, analysisFeedback.meal.mealId)
        assertEquals("제육볶음", analysisFeedback.meal.name)
        assertEquals(Nutrition(calory = 726, carbs = 82, protein = 34, fat = 29), analysisFeedback.meal.nutrition)
        assertEquals("https://image.example/meals/1/100.jpg", analysisFeedback.meal.imageUrl)
        assertNull(analysisFeedback.meal.deletedAt)
    }

    @Test
    fun `사용자가 지운 식사의 평가도 삭제 시각과 함께 준다`() {
        val deletedAt = now.minusSeconds(60)
        every {
            analysisFeedbackRepository.findAnalysisFeedbacksBeforeCursor(
                null,
                null,
                Long.MAX_VALUE,
                PageRequest.of(0, 20),
            )
        } returns SliceImpl(listOf(analysisFeedback(id = 10, mealId = 100)), PageRequest.of(0, 20), false)
        every { mealRepository.findAllById(listOf(100L)) } returns listOf(meal(id = 100, deletedAt = deletedAt))

        val analysisFeedback = adminAnalysisFeedbackService.getAnalysisFeedbacks(null, null, null, 20)
            .analysisFeedbacks.single()

        assertEquals(deletedAt, analysisFeedback.meal.deletedAt)
    }
}
