package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.meal.dto.ServingNutrition
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoodNutritionTest {

    private val updatedAt = Instant.parse("2026-11-16T03:00:00Z")
    private val timeToLive = Duration.ofDays(7)

    private fun createFoodNutrition() = FoodNutrition(
        name = "김치찌개",
        servingCalory = BigDecimal("300.00"),
        servingCarbs = BigDecimal("15.00"),
        servingProtein = BigDecimal("15.00"),
        servingFat = BigDecimal("20.00"),
        iconId = 4L,
    ).apply { this.updatedAt = this@FoodNutritionTest.updatedAt }

    @Test
    fun `값을 구한 지 유효 기간이 지나지 않았으면 만료되지 않았다`() {
        val now = updatedAt.plus(timeToLive).minusSeconds(1)

        assertFalse(createFoodNutrition().isExpired(now, timeToLive))
    }

    @Test
    fun `값을 구한 지 꼭 유효 기간만큼 지났으면 만료됐다`() {
        val now = updatedAt.plus(timeToLive)

        assertTrue(createFoodNutrition().isExpired(now, timeToLive))
    }

    @Test
    fun `새로 구한 값으로 바꾸면 1인분과 아이콘이 바뀌고 값을 구한 시각이 옮겨진다`() {
        val foodNutrition = createFoodNutrition()
        val servingNutrition =
            ServingNutrition(BigDecimal("250.00"), BigDecimal("15.00"), BigDecimal("15.00"), BigDecimal("14.00"))
        val now = updatedAt.plus(Duration.ofDays(8))

        foodNutrition.updateServingNutrition(servingNutrition, iconId = 2L, updatedAt = now)

        assertEquals(servingNutrition, foodNutrition.servingNutrition)
        assertEquals(2L, foodNutrition.iconId)
        assertEquals(now, foodNutrition.updatedAt)
        assertFalse(foodNutrition.isExpired(now, timeToLive))
    }
}
