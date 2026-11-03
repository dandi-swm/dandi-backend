package com.dandi.nyummy.coin.service

import com.dandi.nyummy.coin.dto.ClaimCoinGiftRequest
import com.dandi.nyummy.coin.dto.CoinGiftResponse
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CoinErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import org.springframework.stereotype.Service

/**
 * 코인 충전 수요를 알아보는 이벤트. 실제로 결제하지 않고, 어떤 패키지를 골라도 같은 코인을 선물로 넣어 준다.
 *
 * 패키지의 가격은 앱에 고정돼 있고, 서버는 가격 그룹과 화면의 패키지 번호(1~3)만 받는다.
 * 저장할 때는 번호 하나로 그룹까지 알 수 있게 A 그룹은 1~3, B 그룹은 4~6으로 바꾼다.
 *
 * 지금은 해피케이스라 아무것도 저장하지 않고 고정 값을 반환한다.
 */
@Service
class CoinGiftService {
    companion object {
        private val VARIANTS = setOf("A", "B")
        private val COIN_PACKAGE_IDS = 1L..3L
        private const val GIFT_COIN_QUANTITY = 3000
        private const val STUB_BALANCE = 860
    }

    /**
     * 어떤 패키지를 골라도 [GIFT_COIN_QUANTITY]만큼 선물로 넣어 준다.
     *
     * 가격 비교가 이 이벤트의 목적이라, 그룹을 알 수 없는 요청을 A로 치면 데이터가 섞이므로 거부한다.
     *
     * @param coinPackageVariant 앱이 Remote Config에서 받은 가격 그룹(A 또는 B)
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 가격 그룹이 없거나 A·B가 아닌 경우
     * @throws BusinessException [CoinErrorCode.COIN_PACKAGE_NOT_FOUND] 없는 패키지인 경우
     */
    fun claimCoinGift(userId: Long, coinPackageVariant: String?, request: ClaimCoinGiftRequest): CoinGiftResponse {
        if (coinPackageVariant?.trim() !in VARIANTS) {
            throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE, "가격 그룹은 A 또는 B여야 합니다.")
        }
        if (request.coinPackageId !in COIN_PACKAGE_IDS) {
            throw BusinessException(CoinErrorCode.COIN_PACKAGE_NOT_FOUND)
        }

        return CoinGiftResponse(
            coinQuantity = GIFT_COIN_QUANTITY,
            balance = STUB_BALANCE + GIFT_COIN_QUANTITY,
        )
    }
}
