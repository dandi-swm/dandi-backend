package com.dandi.nyummy.meal.calculator

import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.NutritionHint
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.entity.MealFood
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MealFoodNutritionCalculatorTest {

    private fun createMealFood(portion: String?, servingNutrition: ServingNutrition? = null) = MealFood(
        mealId = 1L,
        mealFoodSnapshotId = null,
        displayOrder = 1,
        name = "부추무침",
        portion = portion?.let(::BigDecimal),
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

    @Test
    fun `인분을 아직 구하지 않은 줄은 1인분 값이 있어도 줄 영양이 없다`() {
        val mealFood = createMealFood(portion = null, createServingNutrition("75", "6", "2", "5"))

        assertNull(calculateMealFoodNutrition(mealFood))
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

    @Test
    fun `인분이 없는 줄이 있으면 식사 합계를 계산하지 않는다`() {
        val mealFoods = listOf(
            createMealFood(portion = "1.0", createServingNutrition("350", "15", "25", "20")),
            createMealFood(portion = null, createServingNutrition("300", "65", "6", "1")),
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

    // 저장하기 전의 값으로 계산

    @Test
    fun `1인분과 인분만으로 구한 줄 영양은 저장된 줄로 구한 값과 같다`() {
        val servingNutrition = createServingNutrition("75", "6", "2", "5")

        assertEquals(
            calculateMealFoodNutrition(createMealFood(portion = "0.5", servingNutrition)),
            calculateMealFoodNutrition(servingNutrition, BigDecimal("0.5")),
        )
    }

    @Test
    fun `1인분과 인분 쌍으로 구한 합계는 저장된 줄로 구한 식사 합계와 같다`() {
        val first = createServingNutrition("276.67", "10.33", "20.5", "15.25")
        val second = createServingNutrition("300", "65", "6", "1")

        assertEquals(
            calculateMealNutrition(
                listOf(createMealFood(portion = "1.5", first), createMealFood(portion = "1.0", second)),
            ),
            calculateTotalNutrition(listOf(first to BigDecimal("1.5"), second to BigDecimal("1.0"))),
        )
    }

    // calculateHintedServingNutrition

    @Test
    fun `칼로리가 적혀 있으면 그 값은 그대로 두고 나머지를 적힌 칼로리 비율로 맞춘다`() {
        // 적힌 520kcal ÷ 구한 650kcal = 0.8배
        val servingNutrition = calculateHintedServingNutrition(
            NutritionHint(calory = BigDecimal("520.00")),
            createServingNutrition("650", "80", "25", "20"),
        )

        assertEquals(createServingNutrition("520.00", "64.00", "20.00", "16.00"), servingNutrition)
    }

    @Test
    fun `칼로리와 함께 적혀 있던 다른 항목도 그대로 두고 빈 항목만 맞춘다`() {
        val servingNutrition = calculateHintedServingNutrition(
            NutritionHint(calory = BigDecimal("100.00"), protein = BigDecimal("20.00")),
            createServingNutrition("200", "10", "30", "4"),
        )

        // 100 ÷ 200 = 0.5배. 단백질은 적힌 20을 쓴다.
        assertEquals(createServingNutrition("100.00", "5.00", "20.00", "2.00"), servingNutrition)
    }

    @Test
    fun `칼로리가 적혀 있지 않으면 적힌 항목은 그대로 두고 빈 항목을 구한 값 그대로 채운다`() {
        val servingNutrition = calculateHintedServingNutrition(
            NutritionHint(protein = BigDecimal("20.00")),
            createServingNutrition("200", "10", "30", "4"),
        )

        assertEquals(createServingNutrition("200.00", "10.00", "20.00", "4.00"), servingNutrition)
    }

    @Test
    fun `구한 칼로리가 0이면 비율을 낼 수 없어 빈 항목을 구한 값 그대로 채운다`() {
        val servingNutrition = calculateHintedServingNutrition(
            NutritionHint(calory = BigDecimal("5.00")),
            createServingNutrition("0", "1", "0", "0"),
        )

        assertEquals(createServingNutrition("5.00", "1.00", "0.00", "0.00"), servingNutrition)
    }

    // calculateTotalAdjustedServingNutritions

    @Test
    fun `식사 전체의 값이 적혀 있으면 줄들의 합이 그 값과 같아지게 1인분을 같은 비율로 맞춘다`() {
        // 닭안심살 132kcal × 1.5인분 + 쌀밥 272kcal × 1인분 = 470kcal → 적힌 409kcal
        val servings = listOf(
            createServingNutrition("132", "0", "30", "1.2") to BigDecimal("1.5"),
            createServingNutrition("272", "60", "6", "0.8") to BigDecimal("1.0"),
        )
        val total = NutritionHint(BigDecimal("409"), BigDecimal("62"), BigDecimal("32.7"), BigDecimal("2.2"))

        val adjusted = calculateTotalAdjustedServingNutritions(servings, total)

        assertEquals(
            Nutrition(calory = 409, carbs = 62, protein = 33, fat = 2),
            calculateTotalNutrition(adjusted.zip(servings.map { it.second })),
        )
        // 음식별 비율은 구한 값 그대로다: 닭 198 : 밥 272
        assertEquals(BigDecimal("114.87"), adjusted[0].calory)
        assertEquals(BigDecimal("236.70"), adjusted[1].calory)
    }

    @Test
    fun `식사 전체의 값에 없는 영양소와 0으로 적힌 영양소는 맞추지 않는다`() {
        val servings = listOf(createServingNutrition("300", "40", "20", "10") to BigDecimal("1.0"))
        val total = NutritionHint(calory = BigDecimal("600"), protein = BigDecimal("0"))

        val adjusted = calculateTotalAdjustedServingNutritions(servings, total).single()

        assertEquals(createServingNutrition("600.00", "40.00", "20.00", "10.00"), adjusted)
    }

    @Test
    fun `줄들의 합이 0인 영양소는 나눌 비율이 없어 맞추지 않는다`() {
        val servings = listOf(createServingNutrition("100", "0", "20", "2") to BigDecimal("1.0"))
        val total = NutritionHint(carbs = BigDecimal("50"))

        val adjusted = calculateTotalAdjustedServingNutritions(servings, total).single()

        assertEquals(BigDecimal("0.00"), adjusted.carbs)
    }
}
