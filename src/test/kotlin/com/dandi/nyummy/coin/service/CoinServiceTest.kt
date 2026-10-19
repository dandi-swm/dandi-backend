package com.dandi.nyummy.coin.service

import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.entity.CoinHistory
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.repository.CoinHistoryRepository
import com.dandi.nyummy.coin.repository.CoinRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoinServiceTest {

    private val userId = 1L
    private val mailId = 10L

    private val coinRepository = mockk<CoinRepository>()
    private val coinHistoryRepository = mockk<CoinHistoryRepository>()

    private val coinService = CoinService(
        coinRepository = coinRepository,
        coinHistoryRepository = coinHistoryRepository,
    )

    // getBalance

    @Test
    fun `코인 행이 있으면 잔액을 반환한다`() {
        every { coinRepository.findByUserId(userId) } returns Coin(userId = userId).apply { balance = 300 }

        assertEquals(300, coinService.getBalance(userId))
    }

    @Test
    fun `코인 행이 없으면 0을 반환한다`() {
        every { coinRepository.findByUserId(userId) } returns null

        assertEquals(0, coinService.getBalance(userId))
    }

    // increaseBalance

    @Test
    fun `잠근 잔액 행에 더하고 늘린 뒤 잔액으로 내역을 남긴다`() {
        val coin = Coin(userId = userId).apply { balance = 300 }
        val savedHistory = slot<CoinHistory>()
        every { coinRepository.findCoinWithLockByUserId(userId) } returns coin
        every { coinHistoryRepository.save(capture(savedHistory)) } answers { firstArg() }

        val balance = coinService.increaseBalance(userId, 270, CoinHistoryCategory.MAIL_CLAIM, sourceId = mailId)

        assertEquals(570, balance)
        assertEquals(570, coin.balance)
        assertEquals(userId, savedHistory.captured.userId)
        assertEquals(CoinHistoryCategory.MAIL_CLAIM, savedHistory.captured.category)
        assertEquals(270, savedHistory.captured.quantity)
        assertEquals(570, savedHistory.captured.balanceAfter)
        assertEquals(mailId, savedHistory.captured.sourceId)
    }

    @Test
    fun `코인 행이 없으면 지급하지 않고 실패한다`() {
        every { coinRepository.findCoinWithLockByUserId(userId) } returns null

        assertFailsWith<IllegalStateException> {
            coinService.increaseBalance(userId, 270, CoinHistoryCategory.MAIL_CLAIM, sourceId = mailId)
        }
        verify(exactly = 0) { coinHistoryRepository.save(any()) }
    }
}
