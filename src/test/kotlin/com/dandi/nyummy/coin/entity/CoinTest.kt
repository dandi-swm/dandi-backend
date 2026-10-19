package com.dandi.nyummy.coin.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoinTest {

    private fun coin(balance: Int) = Coin(userId = 1L).apply { this.balance = balance }

    // increaseBalance

    @Test
    fun `잔액에 지급 수량을 더한다`() {
        val coin = coin(balance = 300)

        coin.increaseBalance(270)

        assertEquals(570, coin.balance)
    }

    @Test
    fun `지급 수량이 0 이하면 잔액을 바꾸지 않고 거부한다`() {
        val coin = coin(balance = 300)

        assertFailsWith<IllegalArgumentException> { coin.increaseBalance(0) }
        assertFailsWith<IllegalArgumentException> { coin.increaseBalance(-10) }
        assertEquals(300, coin.balance)
    }
}
