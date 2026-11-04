package com.dandi.nyummy.coin.service

import com.dandi.nyummy.coin.dto.ClaimCoinGiftRequest
import com.dandi.nyummy.coin.dto.CoinGiftResponse
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.repository.CoinHistoryRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CoinErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 코인 충전 수요를 알아보는 이벤트. 실제로 결제하지 않고, 어떤 패키지를 골라도 같은 코인을 선물로 넣어 준다.
 *
 * 패키지의 가격은 앱에 고정돼 있고, 서버는 가격 그룹과 화면의 패키지 번호(1~3)만 받는다.
 * 저장할 때는 번호 하나로 그룹까지 알 수 있게 A 그룹은 1~3, B 그룹은 4~6으로 바꾼다.
 *
 * 받았는지는 코인 내역으로 판단하므로 이벤트용 테이블을 따로 두지 않는다.
 */
@Service
class CoinGiftService(private val coinService: CoinService, private val coinHistoryRepository: CoinHistoryRepository) {
    companion object {
        /** 화면의 패키지 번호에 더해 저장할 번호를 만드는 값 */
        private val COIN_PACKAGE_OFFSETS = mapOf("A" to 0L, "B" to 3L)
        private val COIN_PACKAGE_IDS = 1L..3L
        private const val GIFT_COIN_QUANTITY = 3000
    }

    /**
     * 어떤 패키지를 골라도 [GIFT_COIN_QUANTITY]만큼 선물로 넣어 준다. 사용자당 한 번만 받는다.
     *
     * 가격 비교가 이 이벤트의 목적이라, 그룹을 알 수 없는 요청을 A로 치면 데이터가 섞이므로 거부한다.
     *
     * 코인 행 잠금이 첫 쿼리다. 같은 사용자의 요청이 하나씩 처리되고, REPEATABLE READ의 스냅샷이 잠금 뒤에 생겨
     * 뒤 요청이 앞 요청이 커밋한 내역을 보므로 동시에 눌러도 한 번만 받는다.
     *
     * @param coinPackageVariant 앱이 Remote Config에서 받은 가격 그룹(A 또는 B)
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 가격 그룹이 없거나 A·B가 아닌 경우
     * @throws BusinessException [CoinErrorCode.COIN_PACKAGE_NOT_FOUND] 없는 패키지인 경우
     * @throws BusinessException [CoinErrorCode.COIN_GIFT_ALREADY_CLAIMED] 이미 선물 코인을 받은 경우
     */
    @Transactional
    fun claimCoinGift(userId: Long, coinPackageVariant: String?, request: ClaimCoinGiftRequest): CoinGiftResponse {
        val coinPackageOffset = COIN_PACKAGE_OFFSETS[coinPackageVariant?.trim()]
            ?: throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE, "가격 그룹은 A 또는 B여야 합니다.")
        if (request.coinPackageId !in COIN_PACKAGE_IDS) {
            throw BusinessException(CoinErrorCode.COIN_PACKAGE_NOT_FOUND)
        }

        coinService.lockCoin(userId)
        if (coinHistoryRepository.existsByUserIdAndCategory(userId, CoinHistoryCategory.COIN_GIFT)) {
            throw BusinessException(CoinErrorCode.COIN_GIFT_ALREADY_CLAIMED)
        }

        val balance = coinService.increaseBalance(
            userId = userId,
            quantity = GIFT_COIN_QUANTITY,
            category = CoinHistoryCategory.COIN_GIFT,
            sourceId = request.coinPackageId + coinPackageOffset,
        )

        return CoinGiftResponse(coinQuantity = GIFT_COIN_QUANTITY, balance = balance)
    }
}
