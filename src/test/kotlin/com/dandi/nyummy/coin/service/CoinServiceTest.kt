package com.dandi.nyummy.coin.service

import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.entity.CoinHistory
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.repository.CoinHistoryRepository
import com.dandi.nyummy.coin.repository.CoinRepository
import com.dandi.nyummy.shop.dto.ItemPurchaseProductNameResult
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.SliceImpl
import org.springframework.test.util.ReflectionTestUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoinServiceTest {

    private val userId = 1L
    private val mailId = 10L

    private val coinRepository = mockk<CoinRepository>()
    private val coinHistoryRepository = mockk<CoinHistoryRepository>()
    private val itemPurchaseProductRepository = mockk<ItemPurchaseProductRepository>()

    private val coinService = CoinService(
        coinRepository = coinRepository,
        coinHistoryRepository = coinHistoryRepository,
        itemPurchaseProductRepository = itemPurchaseProductRepository,
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

    // lockCoin

    @Test
    fun `코인 행을 잠가 반환한다`() {
        val coin = Coin(userId = userId)
        every { coinRepository.findCoinWithLockByUserId(userId) } returns coin

        assertEquals(coin, coinService.lockCoin(userId))
    }

    @Test
    fun `잠글 코인 행이 없으면 실패한다`() {
        every { coinRepository.findCoinWithLockByUserId(userId) } returns null

        assertFailsWith<IllegalStateException> { coinService.lockCoin(userId) }
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

    // decreaseBalance

    @Test
    fun `잠근 잔액 행에서 빼고 음수 수량과 줄인 뒤 잔액으로 내역을 남긴다`() {
        val coin = Coin(userId = userId).apply { balance = 300 }
        val savedHistory = slot<CoinHistory>()
        every { coinHistoryRepository.save(capture(savedHistory)) } answers { firstArg() }

        val balance = coinService.decreaseBalance(coin, 120, CoinHistoryCategory.ROOM_ITEM_PURCHASE, sourceId = 7L)

        assertEquals(180, balance)
        assertEquals(180, coin.balance)
        assertEquals(userId, savedHistory.captured.userId)
        assertEquals(CoinHistoryCategory.ROOM_ITEM_PURCHASE, savedHistory.captured.category)
        assertEquals(-120, savedHistory.captured.quantity)
        assertEquals(180, savedHistory.captured.balanceAfter)
        assertEquals(7L, savedHistory.captured.sourceId)
    }

    @Test
    fun `잔액이 모자라면 내역을 남기지 않고 실패한다`() {
        val coin = Coin(userId = userId).apply { balance = 100 }

        assertFailsWith<IllegalArgumentException> {
            coinService.decreaseBalance(coin, 120, CoinHistoryCategory.ROOM_ITEM_PURCHASE, sourceId = 7L)
        }
        verify(exactly = 0) { coinHistoryRepository.save(any()) }
    }

    // getCoinHistories

    private fun coinHistory(id: Long, category: CoinHistoryCategory, sourceId: Long? = null) = CoinHistory(
        userId = userId,
        category = category,
        quantity = -100,
        balanceAfter = 900,
        sourceId = sourceId,
    ).also { ReflectionTestUtils.setField(it, "id", id) }

    private fun givenCoinHistories(vararg coinHistories: CoinHistory, hasNext: Boolean = false) {
        every { coinHistoryRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, any(), any()) } returns
            SliceImpl(coinHistories.toList(), PageRequest.of(0, 10), hasNext)
    }

    @Test
    fun `첫 요청은 가장 큰 id를 기준으로 size만큼 조회한다`() {
        givenCoinHistories()

        coinService.getCoinHistories(userId, cursor = null, size = 10)

        verify {
            coinHistoryRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, Long.MAX_VALUE, PageRequest.of(0, 10))
        }
    }

    @Test
    fun `다음 묶음이 있으면 마지막 내역 id를 nextCursor로 준다`() {
        givenCoinHistories(
            coinHistory(9, CoinHistoryCategory.MAIL_CLAIM),
            coinHistory(7, CoinHistoryCategory.MAIL_CLAIM),
            hasNext = true,
        )

        val response = coinService.getCoinHistories(userId, cursor = 10, size = 2)

        assertEquals(7, response.nextCursor)
        assertTrue(response.hasNext)
    }

    @Test
    fun `마지막 묶음이면 nextCursor는 null이다`() {
        givenCoinHistories(coinHistory(9, CoinHistoryCategory.MAIL_CLAIM))

        val response = coinService.getCoinHistories(userId, cursor = null, size = 10)

        assertNull(response.nextCursor)
        assertFalse(response.hasNext)
    }

    @Test
    fun `구매 내역이 없으면 상품 이름을 조회하지 않고 우편 내역은 우편 받기로 준다`() {
        givenCoinHistories(coinHistory(9, CoinHistoryCategory.MAIL_CLAIM, sourceId = 3L))

        val response = coinService.getCoinHistories(userId, cursor = null, size = 10)

        assertEquals("우편 받기", response.coinHistories.single().title)
        verify(exactly = 0) { itemPurchaseProductRepository.findItemPurchaseProductNames(any()) }
    }

    @Test
    fun `구매 내역의 문구는 한 번의 쿼리로 읽은 상품 이름으로 만든다`() {
        givenCoinHistories(
            coinHistory(9, CoinHistoryCategory.ROOM_ITEM_PURCHASE, sourceId = 1L),
            coinHistory(8, CoinHistoryCategory.ROOM_ITEM_PURCHASE, sourceId = 2L),
            coinHistory(7, CoinHistoryCategory.ROOM_ITEM_PURCHASE, sourceId = 99L),
            coinHistory(6, CoinHistoryCategory.MAIL_CLAIM, sourceId = 2L),
        )
        every { itemPurchaseProductRepository.findItemPurchaseProductNames(listOf(1L, 2L, 99L)) } returns listOf(
            ItemPurchaseProductNameResult(1L, "발바닥 방석"),
            ItemPurchaseProductNameResult(2L, "호박 쿠션"),
            ItemPurchaseProductNameResult(2L, "호박 쿠션"),
            ItemPurchaseProductNameResult(2L, "스크래처"),
        )

        val response = coinService.getCoinHistories(userId, cursor = null, size = 10)

        assertEquals(
            listOf("발바닥 방석 구매", "호박 쿠션 외 2개 구매", "상점 구매", "우편 받기"),
            response.coinHistories.map { it.title },
        )
        verify(exactly = 1) { itemPurchaseProductRepository.findItemPurchaseProductNames(any()) }
    }

    @Test
    fun `선물 코인 내역은 코인 선물로 주고 상품 이름을 조회하지 않는다`() {
        givenCoinHistories(coinHistory(9, CoinHistoryCategory.COIN_GIFT, sourceId = 3L))

        val response = coinService.getCoinHistories(userId, cursor = null, size = 10)

        assertEquals("코인 선물", response.coinHistories.single().title)
        verify(exactly = 0) { itemPurchaseProductRepository.findItemPurchaseProductNames(any()) }
    }

    @Test
    fun `내역의 수량과 잔액, 시각을 그대로 준다`() {
        val coinHistory = coinHistory(9, CoinHistoryCategory.MAIL_CLAIM)
        givenCoinHistories(coinHistory)

        val response = coinService.getCoinHistories(userId, cursor = null, size = 10).coinHistories.single()

        assertEquals(9, response.coinHistoryId)
        assertEquals(CoinHistoryCategory.MAIL_CLAIM, response.category)
        assertEquals(-100, response.quantity)
        assertEquals(900, response.balanceAfter)
        assertEquals(coinHistory.createdAt, response.createdAt)
    }
}
