package com.dandi.nyummy.shop.calculator

import kotlin.test.Test
import kotlin.test.assertEquals

class ShopPriceCalculatorTest {

    @Test
    fun `할인율이 없으면 정가 그대로다`() {
        assertEquals(650, calculatePurchasePrice(listPrice = 650, discountRate = null))
    }

    @Test
    fun `할인율을 적용하고 소수점 아래는 버린다`() {
        // 650 × 0.7 = 455, 333 × 0.7 = 233.1
        assertEquals(455, calculatePurchasePrice(listPrice = 650, discountRate = 30))
        assertEquals(233, calculatePurchasePrice(listPrice = 333, discountRate = 30))
    }

    @Test
    fun `할인율이 100이면 0 코인이다`() {
        assertEquals(0, calculatePurchasePrice(listPrice = 1000, discountRate = 100))
    }
}
