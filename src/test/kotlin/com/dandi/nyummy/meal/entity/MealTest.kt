package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.enum.MealAnalysisVersion
import com.dandi.nyummy.meal.enum.MealStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MealTest {

    // resetNutrition

    @Test
    fun `합계와 아이콘을 비워도 대표 이름과 고양이 코멘트, 상태는 그대로다`() {
        val meal = Meal(
            status = MealStatus.COMPLETED,
            imageKey = "meals/1/uuid.jpg",
            mealAt = Instant.parse("2026-11-14T03:00:00Z"),
            userId = 1L,
            analysisVersion = MealAnalysisVersion.V2,
        ).apply {
            updateName("불고기 백반")
            updateNutrition(Nutrition(calory = 825, carbs = 88, protein = 44, fat = 31))
            updateIconId(12L)
            updateCatComment("든든하게 먹었다냥")
        }

        meal.resetNutrition()

        assertNull(meal.calory)
        assertNull(meal.carbs)
        assertNull(meal.protein)
        assertNull(meal.fat)
        assertNull(meal.iconId)
        assertEquals("불고기 백반", meal.name)
        assertEquals("든든하게 먹었다냥", meal.catComment)
        assertEquals(MealStatus.COMPLETED, meal.status)
    }
}
