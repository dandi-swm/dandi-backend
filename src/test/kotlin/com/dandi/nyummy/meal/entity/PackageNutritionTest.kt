package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.enum.NetContentUnit
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class PackageNutritionTest {

    private val servingNutrition =
        ServingNutrition(BigDecimal("100.00"), BigDecimal("4.00"), BigDecimal("20.00"), BigDecimal("0.70"))
    private val otherServingNutrition =
        ServingNutrition(BigDecimal("110.00"), BigDecimal("5.00"), BigDecimal("21.00"), BigDecimal("0.80"))

    private fun createPackageNutrition() = PackageNutrition(
        name = "셀렉스 프로핏 딸기초코",
        netContent = BigDecimal("250.0"),
        netContentUnit = NetContentUnit.ML,
        servingCalory = servingNutrition.calory,
        servingCarbs = servingNutrition.carbs,
        servingProtein = servingNutrition.protein,
        servingFat = servingNutrition.fat,
        iconId = 6L,
    )

    @Test
    fun `네 값을 제품 하나의 영양으로 돌려준다`() {
        assertEquals(servingNutrition, createPackageNutrition().servingNutrition)
    }

    @Test
    fun `새로 읽은 값으로 바꾸면 네 값이 바뀌고 이름과 용량과 아이콘은 그대로다`() {
        val packageNutrition = createPackageNutrition()

        packageNutrition.updateServingNutrition(otherServingNutrition)

        assertEquals(otherServingNutrition, packageNutrition.servingNutrition)
        assertEquals("셀렉스 프로핏 딸기초코", packageNutrition.name)
        assertEquals(BigDecimal("250.0"), packageNutrition.netContent)
        assertEquals(NetContentUnit.ML, packageNutrition.netContentUnit)
        assertEquals(6L, packageNutrition.iconId)
    }
}
