package com.dandi.nyummy.coin.service

import com.dandi.nyummy.coin.dto.ClaimCoinGiftRequest
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CoinErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoinGiftServiceTest {

    private val userId = 1L

    private val coinGiftService = CoinGiftService()

    private fun assertBusinessException(errorCode: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException> { block() }
        assertEquals(errorCode, exception.errorCode)
    }

    @Test
    fun `어떤 그룹의 어떤 패키지를 골라도 3000코인을 받는다`() {
        listOf("A", "B").forEach { variant ->
            (1L..3L).forEach { coinPackageId ->
                val response = coinGiftService.claimCoinGift(userId, variant, ClaimCoinGiftRequest(coinPackageId))

                assertEquals(3000, response.coinQuantity)
            }
        }
    }

    @Test
    fun `응답 잔액에는 받은 코인이 더해져 있다`() {
        val response = coinGiftService.claimCoinGift(userId, "A", ClaimCoinGiftRequest(coinPackageId = 1))

        assertEquals(3860, response.balance)
    }

    @Test
    fun `그룹 앞뒤의 공백은 무시한다`() {
        val response = coinGiftService.claimCoinGift(userId, " B ", ClaimCoinGiftRequest(coinPackageId = 1))

        assertEquals(3000, response.coinQuantity)
    }

    @Test
    fun `그룹이 없거나 A와 B가 아니면 거부한다`() {
        listOf(null, "", "C", "a").forEach { variant ->
            assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
                coinGiftService.claimCoinGift(userId, variant, ClaimCoinGiftRequest(coinPackageId = 1))
            }
        }
    }

    @Test
    fun `패키지 번호가 1에서 3 사이가 아니면 거부한다`() {
        listOf(0L, 4L, -1L).forEach { coinPackageId ->
            assertBusinessException(CoinErrorCode.COIN_PACKAGE_NOT_FOUND) {
                coinGiftService.claimCoinGift(userId, "A", ClaimCoinGiftRequest(coinPackageId))
            }
        }
    }
}
