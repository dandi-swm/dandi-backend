package com.dandi.nyummy.meal.calculator

import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.entity.MealFood
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MealFoodNutritionCalculatorTest {

    private fun createMealFood(portion: String, servingNutrition: ServingNutrition? = null) = MealFood(
        mealId = 1L,
        mealFoodSnapshotId = null,
        displayOrder = 1,
        name = "부추무침",
        portion = BigDecimal(portion),
    ).apply {
        servingCalory = servingNutrition?.calory
        servingCarbs = servingNutrition?.carbs
        servingProtein = servingNutrition?.protein
        servingFat = servingNutrition?.fat
    }

    private fun createServingNutrition(calory: String, carbs: String, protein: String, fat: String) = ServingNutrition(
        calory = BigDecimal(calory),
        carbs = BigDecimal(carbs),
        protein = BigDecimal(protein),
        fat = BigDecimal(fat),
    )

    // calculateMealFoodNutrition

    @Test
    fun `줄 영양은 1인분에 인분을 곱해 정수로 반올림한다`() {
        // 75 × 0.5 = 37.5 → 38, 5 × 0.5 = 2.5 → 3
        val mealFood = createMealFood(portion = "0.5", createServingNutrition("75", "6", "2", "5"))

        assertEquals(Nutrition(calory = 38, carbs = 3, protein = 1, fat = 3), calculateMealFoodNutrition(mealFood))
    }

    @Test
    fun `1인분 값을 아직 구하지 않은 줄은 줄 영양이 없다`() {
        assertNull(calculateMealFoodNutrition(createMealFood(portion = "1.0")))
    }

    // calculateMealNutrition

    @Test
    fun `식사 합계는 줄을 소수로 더한 뒤 한 번만 반올림한다`() {
        // 줄마다 반올림하면 38 + 38 = 76이지만, 소수로 더하면 37.5 + 37.5 = 75다.
        val mealFoods = List(2) { createMealFood(portion = "0.5", createServingNutrition("75", "6", "2", "5")) }

        assertEquals(Nutrition(calory = 75, carbs = 6, protein = 2, fat = 5), calculateMealNutrition(mealFoods))
    }

    @Test
    fun `식사 합계는 줄마다 인분을 곱해 더한다`() {
        val mealFoods = listOf(
            createMealFood(portion = "1.5", createServingNutrition("350", "15", "25", "20")),
            createMealFood(portion = "1.0", createServingNutrition("300", "65", "6", "1")),
        )

        assertEquals(Nutrition(calory = 825, carbs = 88, protein = 44, fat = 31), calculateMealNutrition(mealFoods))
    }

    @Test
    fun `1인분 값이 없는 줄이 있으면 식사 합계를 계산하지 않는다`() {
        val mealFoods = listOf(
            createMealFood(portion = "1.0", createServingNutrition("350", "15", "25", "20")),
            createMealFood(portion = "1.0"),
        )

        assertFailsWith<IllegalStateException> { calculateMealNutrition(mealFoods) }
    }

    // calculateServingNutrition

    @Test
    fun `고친 줄 영양을 인분으로 나눠 소수 둘째 자리까지 1인분을 구한다`() {
        val servingNutrition = calculateServingNutrition(
            Nutrition(calory = 415, carbs = 50, protein = 20, fat = 10),
            portion = BigDecimal("1.5"),
        )

        assertEquals(createServingNutrition("276.67", "33.33", "13.33", "6.67"), servingNutrition)
    }

    @Test
    fun `고친 값으로 구한 1인분에 같은 인분을 곱하면 고친 값이 그대로 나온다`() {
        val portions = generateSequence(MealFood.MIN_PORTION) { it + MealFood.PORTION_STEP }
            .takeWhile { it <= MealFood.MAX_PORTION }
            .toList()
        val nutrition = Nutrition(calory = 9999, carbs = 415, protein = 7, fat = 1)

        portions.forEach { portion ->
            val mealFood = createMealFood(portion.toPlainString(), calculateServingNutrition(nutrition, portion))

            assertEquals(nutrition, calculateMealFoodNutrition(mealFood), "인분 $portion")
        }
    }
}
