package com.dandi.nyummy.coin.service

import com.dandi.nyummy.coin.dto.ClaimCoinGiftRequest
import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.repository.CoinHistoryRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CoinErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoinGiftServiceTest {

    private val userId = 1L

    private val coinService = mockk<CoinService>()
    private val coinHistoryRepository = mockk<CoinHistoryRepository>()

    private val coinGiftService = CoinGiftService(coinService, coinHistoryRepository)

    private fun assertBusinessException(errorCode: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException> { block() }
        assertEquals(errorCode, exception.errorCode)
    }

    private fun stubCoinGift(isClaimed: Boolean = false, balance: Int = 3860) {
        every { coinService.lockCoin(userId) } returns Coin(userId = userId)
        every {
            coinHistoryRepository.existsByUserIdAndCategory(userId, CoinHistoryCategory.COIN_GIFT)
        } returns isClaimed
        every {
            coinService.increaseBalance(userId, 3000, CoinHistoryCategory.COIN_GIFT, any())
        } returns balance
    }

    @Test
    fun `처음 받으면 3000코인을 넣고 늘어난 잔액을 반환한다`() {
        stubCoinGift(balance = 3860)

        val response = coinGiftService.claimCoinGift(userId, "A", ClaimCoinGiftRequest(coinPackageId = 1))

        assertEquals(3000, response.coinQuantity)
        assertEquals(3860, response.balance)
    }

    @Test
    fun `코인 행을 잠근 뒤에 받은 적이 있는지 확인하고 코인을 넣는다`() {
        stubCoinGift()

        coinGiftService.claimCoinGift(userId, "A", ClaimCoinGiftRequest(coinPackageId = 1))

        verifyOrder {
            coinService.lockCoin(userId)
            coinHistoryRepository.existsByUserIdAndCategory(userId, CoinHistoryCategory.COIN_GIFT)
            coinService.increaseBalance(userId, 3000, CoinHistoryCategory.COIN_GIFT, any())
        }
    }

    @Test
    fun `A 그룹은 패키지 번호 1에서 3으로, B 그룹은 4에서 6으로 내역에 남긴다`() {
        stubCoinGift()
        val sourceIds = mapOf(
            ("A" to 1L) to 1L,
            ("A" to 2L) to 2L,
            ("A" to 3L) to 3L,
            ("B" to 1L) to 4L,
            ("B" to 2L) to 5L,
            ("B" to 3L) to 6L,
        )

        sourceIds.forEach { (requested, sourceId) ->
            val (variant, coinPackageId) = requested

            coinGiftService.claimCoinGift(userId, variant, ClaimCoinGiftRequest(coinPackageId))

            verify(exactly = 1) {
                coinService.increaseBalance(userId, 3000, CoinHistoryCategory.COIN_GIFT, sourceId)
            }
        }
    }

    @Test
    fun `그룹 앞뒤의 공백은 무시한다`() {
        stubCoinGift()

        coinGiftService.claimCoinGift(userId, " B ", ClaimCoinGiftRequest(coinPackageId = 1))

        verify(exactly = 1) { coinService.increaseBalance(userId, 3000, CoinHistoryCategory.COIN_GIFT, 4L) }
    }

    @Test
    fun `이미 받았으면 거부하고 코인을 넣지 않는다`() {
        stubCoinGift(isClaimed = true)

        assertBusinessException(CoinErrorCode.COIN_GIFT_ALREADY_CLAIMED) {
            coinGiftService.claimCoinGift(userId, "A", ClaimCoinGiftRequest(coinPackageId = 1))
        }

        verify(exactly = 0) { coinService.increaseBalance(any(), any(), any(), any()) }
    }

    @Test
    fun `그룹이 없거나 A와 B가 아니면 코인 행을 잠그지 않고 거부한다`() {
        listOf(null, "", "C", "a").forEach { variant ->
            assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
                coinGiftService.claimCoinGift(userId, variant, ClaimCoinGiftRequest(coinPackageId = 1))
            }
        }

        verify(exactly = 0) { coinService.lockCoin(any()) }
    }

    @Test
    fun `패키지 번호가 1에서 3 사이가 아니면 코인 행을 잠그지 않고 거부한다`() {
        listOf(0L, 4L, -1L).forEach { coinPackageId ->
            assertBusinessException(CoinErrorCode.COIN_PACKAGE_NOT_FOUND) {
                coinGiftService.claimCoinGift(userId, "A", ClaimCoinGiftRequest(coinPackageId))
            }
        }

        verify(exactly = 0) { coinService.lockCoin(any()) }
    }
}
