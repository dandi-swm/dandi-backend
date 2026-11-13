package com.dandi.nyummy.meal.calculator

import com.dandi.nyummy.meal.dto.CaloryShareBalanceResult
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.enum.MacroNutrient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaloryShareCalculatorTest {

    // 목표 구성비는 탄수화물 49, 단백질 20, 지방 31이다(1000 : 400 : 630kcal).
    private val target = Nutrition(calory = 2000, carbs = 250, protein = 100, fat = 70)

    // calculateCaloryShares

    @Test
    fun `구성비는 탄단지 칼로리 합에서 각 영양소가 차지하는 비율이다`() {
        // 탄수화물 100g = 400kcal, 단백질 50g = 200kcal, 지방 0g
        val shares = calculateCaloryShares(Nutrition(calory = 600, carbs = 100, protein = 50, fat = 0))

        assertEquals(2.0 / 3, shares.getValue(MacroNutrient.CARBS), 1e-9)
        assertEquals(1.0 / 3, shares.getValue(MacroNutrient.PROTEIN), 1e-9)
        assertEquals(0.0, shares.getValue(MacroNutrient.FAT), 1e-9)
    }

    @Test
    fun `탄단지 기록이 없으면 구성비는 모두 0이다`() {
        val shares = calculateCaloryShares(Nutrition(calory = 500, carbs = 0, protein = 0, fat = 0))

        assertEquals(setOf(0.0), shares.values.toSet())
    }

    // calculateCaloryShareBalance

    @Test
    fun `목표 구성비에서 20퍼센트 넘게 벗어난 영양소를 많음과 부족으로 나눈다`() {
        // 구성비 72 : 12 : 16 → 탄수화물 +46, 단백질 -39, 지방 -48
        val balance = calculateCaloryShareBalance(Nutrition(calory = 1670, carbs = 300, protein = 50, fat = 30), target)

        assertEquals(
            CaloryShareBalanceResult(
                excessNutrients = listOf(MacroNutrient.CARBS),
                lackingNutrients = listOf(MacroNutrient.PROTEIN, MacroNutrient.FAT),
            ),
            balance,
        )
    }

    @Test
    fun `셋 다 목표 구성비에서 20퍼센트 안이면 많은 것도 부족한 것도 없다`() {
        // 구성비 49 : 20 : 31
        val balance = calculateCaloryShareBalance(Nutrition(calory = 733, carbs = 88, protein = 36, fat = 25), target)

        assertEquals(CaloryShareBalanceResult(excessNutrients = emptyList(), lackingNutrients = emptyList()), balance)
    }

    @Test
    fun `먹은 양이 목표보다 훨씬 적어도 구성이 목표와 같으면 부족하다고 하지 않는다`() {
        val balance = calculateCaloryShareBalance(Nutrition(calory = 600, carbs = 75, protein = 30, fat = 21), target)

        assertEquals(CaloryShareBalanceResult(excessNutrients = emptyList(), lackingNutrients = emptyList()), balance)
    }

    @Test
    fun `탄단지 기록이 없으면 판정하지 않는다`() {
        assertNull(calculateCaloryShareBalance(Nutrition(calory = 500, carbs = 0, protein = 0, fat = 0), target))
    }
}
