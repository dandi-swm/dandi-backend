package com.dandi.nyummy.shop.calculator

import kotlin.test.Test
import kotlin.test.assertEquals

class ItemPurchaseRefundCalculatorTest {

    /** 정가 [listPrices]인 구성품을 이 순서로 하나씩 환불할 때 매번 돌려주는 코인 */
    private fun calculateRefundCoinQuantities(purchasePrice: Int, listPrices: List<Int>): List<Int> {
        var remainingListPrice = listPrices.sum()
        var refundedCoinQuantity = 0

        return listPrices.map { listPrice ->
            remainingListPrice -= listPrice
            calculateRefundCoinQuantity(purchasePrice, remainingListPrice, refundedCoinQuantity)
                .also { refundedCoinQuantity += it }
        }
    }

    @Test
    fun `단품을 환불하면 정가가 아니라 결제가를 돌려준다`() {
        assertEquals(475, calculateRefundCoinQuantity(475, remainingListPrice = 0, refundedCoinQuantity = 0))
    }

    @Test
    fun `세트를 나눠서 환불하면 남은 구성품을 정가로 계산한 차액을 돌려준다`() {
        assertEquals(listOf(300, 300, 200), calculateRefundCoinQuantities(800, listOf(500, 300, 200)))
    }

    @Test
    fun `남은 구성품 정가 합이 결제가 이상이면 0코인이다`() {
        assertEquals(listOf(0, 300, 500), calculateRefundCoinQuantities(800, listOf(200, 300, 500)))
    }

    @Test
    fun `어떤 순서로 나눠서 환불해도 합계는 결제가와 같다`() {
        val orders = listOf(
            listOf(500, 300, 200),
            listOf(500, 200, 300),
            listOf(300, 500, 200),
            listOf(300, 200, 500),
            listOf(200, 500, 300),
            listOf(200, 300, 500),
        )

        orders.forEach { order ->
            assertEquals(800, calculateRefundCoinQuantities(800, order).sum(), "순서: $order")
        }
    }

    @Test
    fun `할인 없이 산 세트는 구성품 정가만큼 돌려준다`() {
        assertEquals(listOf(500, 300), calculateRefundCoinQuantities(800, listOf(500, 300)))
    }

    @Test
    fun `0코인으로 산 상품은 0코인을 돌려준다`() {
        assertEquals(0, calculateRefundCoinQuantity(0, remainingListPrice = 0, refundedCoinQuantity = 0))
    }
}
