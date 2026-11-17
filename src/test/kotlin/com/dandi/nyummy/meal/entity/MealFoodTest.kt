package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.meal.dto.NutritionHint
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.enum.NetContentUnit
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MealFoodTest {

    private val foundServingNutrition = ServingNutrition(
        calory = BigDecimal("350"),
        carbs = BigDecimal("15.5"),
        protein = BigDecimal("25.25"),
        fat = BigDecimal("20"),
    )
    private val fixedServingNutrition = ServingNutrition(
        calory = BigDecimal("276.67"),
        carbs = BigDecimal("10"),
        protein = BigDecimal("20"),
        fat = BigDecimal("15"),
    )

    /** 1인분과 아이콘을 Worker가 구해 둔, 고정하지 않은 줄. */
    private fun createMealFood() = MealFood(
        mealId = 1L,
        mealFoodSnapshotId = 11L,
        displayOrder = 1,
        name = "불고기",
        portion = BigDecimal("1.5"),
    ).apply {
        updateServingNutrition(foundServingNutrition)
        updateIconId(12L)
    }

    // updateName

    @Test
    fun `이름이 바뀌면 고정하지 않은 1인분과 아이콘을 비운다`() {
        val mealFood = createMealFood()

        mealFood.updateName("제육볶음")

        assertEquals("제육볶음", mealFood.name)
        assertNull(mealFood.servingNutrition)
        assertNull(mealFood.iconId)
    }

    @Test
    fun `같은 이름이면 1인분과 아이콘을 그대로 둔다`() {
        val mealFood = createMealFood()

        mealFood.updateName("불고기")

        assertEquals(foundServingNutrition, mealFood.servingNutrition)
        assertEquals(12L, mealFood.iconId)
    }

    @Test
    fun `영양을 고정한 줄은 이름이 바뀌어도 1인분을 그대로 두고 고정하지 않은 아이콘만 비운다`() {
        val mealFood = createMealFood().apply { updateFixedServingNutrition(fixedServingNutrition) }

        mealFood.updateName("제육볶음")

        assertEquals(fixedServingNutrition, mealFood.servingNutrition)
        assertTrue(mealFood.isNutritionFixed)
        assertNull(mealFood.iconId)
    }

    @Test
    fun `아이콘을 고정한 줄은 이름이 바뀌어도 아이콘을 그대로 두고 고정하지 않은 1인분만 비운다`() {
        val mealFood = createMealFood().apply { updateFixedIconId(7L) }

        mealFood.updateName("제육볶음")

        assertEquals(7L, mealFood.iconId)
        assertTrue(mealFood.isIconFixed)
        assertNull(mealFood.servingNutrition)
    }

    @Test
    fun `이름이 바뀌면 용량을 비운다`() {
        val mealFood = createMealFood().apply { updateNetContent(BigDecimal("250.0"), NetContentUnit.ML) }

        mealFood.updateName("제육볶음")

        assertNull(mealFood.netContent)
        assertNull(mealFood.netContentUnit)
    }

    @Test
    fun `같은 이름이면 용량을 그대로 둔다`() {
        val mealFood = createMealFood().apply { updateNetContent(BigDecimal("250.0"), NetContentUnit.ML) }

        mealFood.updateName("불고기")

        assertEquals(BigDecimal("250.0"), mealFood.netContent)
        assertEquals(NetContentUnit.ML, mealFood.netContentUnit)
    }

    @Test
    fun `영양과 아이콘을 고정한 줄도 이름이 바뀌면 용량은 비운다`() {
        val mealFood = createMealFood().apply {
            updateFixedServingNutrition(fixedServingNutrition)
            updateFixedIconId(7L)
            updateNetContent(BigDecimal("120.0"), NetContentUnit.G)
        }

        mealFood.updateName("제육볶음")

        assertNull(mealFood.netContent)
        assertNull(mealFood.netContentUnit)
        assertEquals(fixedServingNutrition, mealFood.servingNutrition)
        assertEquals(7L, mealFood.iconId)
    }

    // updateFixedServingNutrition·updateFixedIconId

    @Test
    fun `직접 고친 영양의 1인분을 저장하면 영양이 고정되고 아이콘 고정은 바뀌지 않는다`() {
        val mealFood = createMealFood()

        mealFood.updateFixedServingNutrition(fixedServingNutrition)

        assertEquals(fixedServingNutrition, mealFood.servingNutrition)
        assertTrue(mealFood.isNutritionFixed)
        assertFalse(mealFood.isIconFixed)
    }

    @Test
    fun `직접 고른 아이콘을 저장하면 아이콘이 고정되고 영양 고정은 바뀌지 않는다`() {
        val mealFood = createMealFood()

        mealFood.updateFixedIconId(7L)

        assertEquals(7L, mealFood.iconId)
        assertTrue(mealFood.isIconFixed)
        assertFalse(mealFood.isNutritionFixed)
    }

    @Test
    fun `Worker가 구한 1인분과 아이콘을 저장해도 고정되지 않는다`() {
        val mealFood = createMealFood()

        assertFalse(mealFood.isNutritionFixed)
        assertFalse(mealFood.isIconFixed)
    }

    // updatePortion·updateDisplayOrder

    @Test
    fun `인분과 순서를 바꿔도 1인분과 아이콘은 그대로다`() {
        val mealFood = createMealFood()

        mealFood.updatePortion(BigDecimal("2.0"))
        mealFood.updateDisplayOrder(3)

        assertEquals(BigDecimal("2.0"), mealFood.portion)
        assertEquals(3, mealFood.displayOrder)
        assertEquals(foundServingNutrition, mealFood.servingNutrition)
        assertEquals(12L, mealFood.iconId)
    }

    // updateNutritionHint, nutritionHint, updateEmptyServingNutrition

    private fun createEmptyMealFood() = MealFood(
        mealId = 1L,
        mealFoodSnapshotId = 11L,
        displayOrder = 1,
        name = "제육덮밥",
        portion = MealFood.DEFAULT_PORTION,
    )

    @Test
    fun `사진에 일부만 적혀 있던 줄은 적힌 항목만 차 있고 그 값을 힌트로 돌려준다`() {
        val mealFood = createEmptyMealFood().apply { updateNutritionHint(NutritionHint(calory = BigDecimal("780.00"))) }

        assertNull(mealFood.servingNutrition)
        assertEquals(NutritionHint(calory = BigDecimal("780.00")), mealFood.nutritionHint)
    }

    @Test
    fun `값이 하나도 없거나 넷이 다 찬 줄은 힌트가 없다`() {
        assertNull(createEmptyMealFood().nutritionHint)
        assertNull(createMealFood().nutritionHint)
    }

    @Test
    fun `빈 항목만 채우면 사진에 적혀 있던 값은 그대로이고 나머지만 구한 값이 된다`() {
        val mealFood = createEmptyMealFood().apply { updateNutritionHint(NutritionHint(calory = BigDecimal("780.00"))) }

        mealFood.updateEmptyServingNutrition(foundServingNutrition)

        assertEquals(
            ServingNutrition(BigDecimal("780.00"), BigDecimal("15.5"), BigDecimal("25.25"), BigDecimal("20")),
            mealFood.servingNutrition,
        )
        assertNull(mealFood.nutritionHint)
        assertFalse(mealFood.isNutritionFixed)
    }

    @Test
    fun `넷이 다 비어 있던 줄에 빈 항목만 채우면 구한 값이 그대로 들어간다`() {
        val mealFood = createEmptyMealFood()

        mealFood.updateEmptyServingNutrition(foundServingNutrition)

        assertEquals(foundServingNutrition, mealFood.servingNutrition)
    }
}
