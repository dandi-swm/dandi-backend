package com.dandi.nyummy.coin.controller

import com.dandi.nyummy.coin.dto.ClaimCoinGiftRequest
import com.dandi.nyummy.coin.dto.CoinGiftResponse
import com.dandi.nyummy.coin.service.CoinGiftService
import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.CoinErrorCode
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "CoinGift", description = "코인 충전 수요를 알아보는 이벤트 API. 실제로 결제하지 않는다")
@RestController
@RequestMapping("/api/v1/coins")
class CoinGiftController(private val coinGiftService: CoinGiftService) {

    @Operation(
        summary = "선물 코인 받기",
        description = "실제로 결제하지 않고, 어떤 패키지를 골라도 3000코인을 선물로 넣어 준다. " +
            "coinPackageId는 A 그룹 1(1,000원)·2(5,000원)·3(10,000원), B 그룹 4(1,500원)·5(7,500원)·6(15,000원)이다. " +
            "코인은 사용자당 한 번만 받으며, 다시 요청하면 코인을 더 주지 않고 200으로 현재 잔액을 준다. " +
            "현재는 저장하지 않고 고정 응답을 반환한다.",
    )
    @ApiErrorCodes(coin = [CoinErrorCode.COIN_PACKAGE_NOT_FOUND])
    @PostMapping("/gifts")
    fun claimCoinGift(@CurrentUser user: AuthUser, @RequestBody request: ClaimCoinGiftRequest): CoinGiftResponse =
        coinGiftService.claimCoinGift(user.userId, request)
}
