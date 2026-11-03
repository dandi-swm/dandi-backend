package com.dandi.nyummy.coin.service

import com.dandi.nyummy.coin.dto.ClaimCoinGiftRequest
import com.dandi.nyummy.coin.dto.CoinGiftResponse
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CoinErrorCode
import org.springframework.stereotype.Service

/**
 * 코인 충전 수요를 알아보는 이벤트. 실제로 결제하지 않고, 어떤 패키지를 골라도 같은 코인을 선물로 넣어 준다.
 *
 * 패키지의 가격과 A/B 그룹은 앱에 고정돼 있고, 서버는 패키지 번호만 받는다. 1~3은 A 그룹, 4~6은 B 그룹이다.
 *
 * 지금은 해피케이스라 아무것도 저장하지 않고 고정 값을 반환한다.
 */
@Service
class CoinGiftService {
    companion object {
        private val COIN_PACKAGE_IDS = 1L..6L
        private const val GIFT_COIN_QUANTITY = 3000
        private const val STUB_BALANCE = 860
    }

    /**
     * 어떤 패키지를 골라도 [GIFT_COIN_QUANTITY]만큼 선물로 넣어 준다.
     *
     * @throws BusinessException [CoinErrorCode.COIN_PACKAGE_NOT_FOUND] 없는 패키지인 경우
     */
    fun claimCoinGift(userId: Long, request: ClaimCoinGiftRequest): CoinGiftResponse {
        if (request.coinPackageId !in COIN_PACKAGE_IDS) {
            throw BusinessException(CoinErrorCode.COIN_PACKAGE_NOT_FOUND)
        }

        return CoinGiftResponse(
            coinQuantity = GIFT_COIN_QUANTITY,
            balance = STUB_BALANCE + GIFT_COIN_QUANTITY,
        )
    }
}
