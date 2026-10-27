package com.dandi.nyummy.coin.controller

import com.dandi.nyummy.coin.dto.CoinHistoriesResponse
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Coin", description = "코인 내역 조회 API")
@RestController
@RequestMapping("/api/v1/coins")
class CoinController(private val coinService: CoinService) {

    @Operation(
        summary = "코인 내역 조회",
        description = "코인을 얻고 쓴 내역을 최신순으로 조회한다. 무한 스크롤용 cursor 방식이다. " +
            "첫 요청은 cursor 없이 보내고, 다음 요청부터는 직전 응답의 nextCursor를 cursor로 보낸다. " +
            "hasNext가 false면 마지막 묶음이며 nextCursor는 null이다. quantity는 부호가 있다(+ 획득, − 사용). " +
            "현재는 요청과 관계없이 고정 응답을 반환한다.",
    )
    @GetMapping("/histories")
    fun getCoinHistories(
        @CurrentUser user: AuthUser,
        @Parameter(description = "직전 응답의 nextCursor. 첫 요청에서는 생략한다.") @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "한 번에 조회할 개수 (1~50, 기본 20)")
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(50)
        size: Int,
    ): CoinHistoriesResponse = coinService.getCoinHistories(user.userId, cursor, size)
}
