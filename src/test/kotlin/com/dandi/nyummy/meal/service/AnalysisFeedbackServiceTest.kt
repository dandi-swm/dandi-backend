package com.dandi.nyummy.meal.service

import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.CreateAnalysisFeedbackRequest
import com.dandi.nyummy.meal.entity.AnalysisFeedback
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.AnalysisFeedbackRepository
import com.dandi.nyummy.meal.repository.MealRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 분석 평가 등록을 다룬다.
 */
class AnalysisFeedbackServiceTest {

    private val now = Instant.parse("2026-11-25T03:00:00Z")
    private val userId = 1L
    private val mealId = 100L

    private val mealRepository = mockk<MealRepository>()
    private val analysisFeedbackRepository = mockk<AnalysisFeedbackRepository>()
    private val mealProperties = mockk<MealProperties>()

    private val analysisFeedbackService = AnalysisFeedbackService(
        mealRepository = mealRepository,
        analysisFeedbackRepository = analysisFeedbackRepository,
        mealProperties = mealProperties,
    )

    private fun meal() = Meal(
        status = MealStatus.COMPLETED,
        imageKey = "meals/$userId/$mealId.jpg",
        mealAt = now,
        userId = userId,
    ).apply {
        // id는 DB가 채우는 val이라 테스트에서만 직접 넣는다.
        ReflectionTestUtils.setField(this, "id", mealId)
    }

    @Test
    fun `평가에 요청의 앱 언어를 남긴다`() {
        val savedAnalysisFeedback = slot<AnalysisFeedback>()
        every { mealRepository.findMealByIdAndDeletedAtIsNull(mealId) } returns meal()
        every { analysisFeedbackRepository.existsByMealId(mealId) } returns false
        every { analysisFeedbackRepository.save(capture(savedAnalysisFeedback)) } answers { firstArg() }

        analysisFeedbackService.createAnalysisFeedback(
            userId = userId,
            mealId = mealId,
            request = CreateAnalysisFeedbackRequest(rating = 2.5f, reason = "量が少なく出ました"),
            language = Language.JA,
        )

        assertEquals(Language.JA, savedAnalysisFeedback.captured.language)
        assertEquals(2.5f, savedAnalysisFeedback.captured.rating)
        assertEquals("量が少なく出ました", savedAnalysisFeedback.captured.reason)
        assertEquals(mealId, savedAnalysisFeedback.captured.mealId)
        assertEquals(userId, savedAnalysisFeedback.captured.userId)
    }
}
