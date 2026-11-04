package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class CoinErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    COIN_PACKAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "api.coin.coinPackageNotFound", "요청한 coinPackageId가 존재하지 않습니다."),
    COIN_GIFT_ALREADY_CLAIMED(HttpStatus.CONFLICT, "api.coin.coinGiftAlreadyClaimed", "이미 선물 코인을 받았습니다."),
}
