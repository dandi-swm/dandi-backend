package com.dandi.nyummy.shop.calculator

import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.entity.PromotionProduct
import com.dandi.nyummy.shop.enum.PromotionCategory
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
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

    private fun promotion(id: Long, discountRate: Int?) = Promotion(
        name = "프로모션 $id",
        category = PromotionCategory.GENERAL,
        discountRate = discountRate,
        startsAt = Instant.parse("2026-11-01T00:00:00Z"),
    ).also { ReflectionTestUtils.setField(it, "id", id) }

    @Test
    fun `상품이 든 프로모션 중 할인율이 가장 큰 것을 고른다`() {
        val promotions = listOf(promotion(1, 30), promotion(2, 40), promotion(3, null))
        val promotionProducts = listOf(
            PromotionProduct(promotionId = 1, productId = 101, displayOrder = 0),
            PromotionProduct(promotionId = 2, productId = 101, displayOrder = 0),
            PromotionProduct(promotionId = 1, productId = 102, displayOrder = 1),
            PromotionProduct(promotionId = 3, productId = 103, displayOrder = 0),
        )

        val discountRates = calculateMaxDiscountRates(promotions, promotionProducts)

        // 103은 할인 없는 프로모션에만 들어 결과에 없다
        assertEquals(mapOf(101L to 40, 102L to 30), discountRates)
    }

    @Test
    fun `넘기지 않은 프로모션의 진열 상품은 무시한다`() {
        val promotionProducts = listOf(PromotionProduct(promotionId = 9, productId = 101, displayOrder = 0))

        assertEquals(emptyMap(), calculateMaxDiscountRates(listOf(promotion(1, 30)), promotionProducts))
    }
}
