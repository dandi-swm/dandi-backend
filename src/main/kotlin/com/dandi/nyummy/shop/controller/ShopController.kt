package com.dandi.nyummy.shop.controller

import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import com.dandi.nyummy.shop.dto.PromotionProductsResponse
import com.dandi.nyummy.shop.dto.ShopItemsResponse
import com.dandi.nyummy.shop.dto.ShopMeResponse
import com.dandi.nyummy.shop.dto.ShopPageResponse
import com.dandi.nyummy.shop.enum.ProductSort
import com.dandi.nyummy.shop.enum.ShopCategory
import com.dandi.nyummy.shop.enum.ShopTab
import com.dandi.nyummy.shop.service.ShopService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Shop", description = "상점 추천 탭(SDUI) · 상품 목록 · 프로모션 상품 목록 · 상단 바 API")
@RestController
@RequestMapping("/api/v1/shop")
class ShopController(private val shopService: ShopService) {

    companion object {
        private const val SHOP_LAYOUT_VARIANT = "X-Shop-Layout-Variant"
    }

    @Operation(
        summary = "추천 탭 조회 (SDUI)",
        description = "추천 탭을 그릴 SDUI 칸 목록을 위에서 아래 순서로 조회한다. 칸은 viewType마다 content 모양이 정해져 있고, " +
            "앱은 모르는 viewType이나 필수 필드가 빠진 칸만 빼고 그린다. 상품이 없는 칸은 위 SectionHeader까지 같이 빠진다. " +
            "A/B 그룹은 앱이 Remote Config에서 받은 값을 X-Shop-Layout-Variant 헤더(A 또는 B)로 보낸다. " +
            "헤더가 없거나 모르는 값이면 A다. A는 배너 → 오늘의 할인 → 인기 순, B는 배너 → 인기 → 오늘의 할인 순이다. " +
            "배너는 진행 중이고 대상인 프로모션의 노출 중 배너이고, 할인율이 있는 프로모션만 묶음 가격(price)을 준다. " +
            "오늘의 할인은 진행 중인 오늘의 할인 프로모션의 판매 중 상품 3개, 인기는 인테리어 상품 판매량순 4개다. " +
            "상품 가격은 대상인 프로모션 중 할인율이 가장 큰 것으로 계산한다.",
    )
    @GetMapping("/pages/recommend")
    fun getRecommendPage(
        @CurrentUser user: AuthUser,
        @Parameter(description = "앱이 Remote Config에서 받은 추천 탭 배치 그룹 (A 또는 B)")
        @RequestHeader(SHOP_LAYOUT_VARIANT, required = false)
        shopLayoutVariant: String?,
    ): ShopPageResponse = shopService.getRecommendPage(user.userId, shopLayoutVariant)

    @Operation(
        summary = "상품 목록 조회",
        description = "인테리어·방 배경 탭의 판매 중인 상품을 조회한다. 무한 스크롤용 cursor 방식이다. " +
            "category는 인테리어 탭의 분류 칩이고, 없으면 탭 전체다. LIMITED는 분류와 관계없이 기간 한정 상품만 거른다. " +
            "방 배경 탭은 분류 칩이 없어 category를 보내면 400이다. 보유 숨기기는 앱이 거른다. " +
            "첫 요청은 cursor 없이 보내고, 다음 요청부터는 직전 응답의 nextCursor를 cursor로 보낸다. " +
            "cursor가 목록에 없는 상품이면 400이다. 현재는 고정 상품 목록에서 거르고 정렬한 결과를 반환한다.",
    )
    @GetMapping("/products")
    fun getProducts(
        @CurrentUser user: AuthUser,
        @Parameter(description = "상점 탭") @RequestParam("tab") tab: ShopTab,
        @Parameter(description = "분류 칩. 생략하면 탭 전체") @RequestParam(required = false) category: ShopCategory?,
        @Parameter(description = "정렬 기준 (기본 NEW)") @RequestParam(defaultValue = "NEW") sort: ProductSort,
        @Parameter(description = "직전 응답의 nextCursor. 첫 요청에서는 생략한다.") @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "한 번에 조회할 개수 (1~50, 기본 20)")
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(50)
        size: Int,
    ): ShopItemsResponse = shopService.getProducts(user.userId, tab, category, sort, cursor, size)

    @Operation(
        summary = "프로모션 상품 목록 조회",
        description = "추천 탭 배너를 누르면 여는 프로모션(신입 집사 환영, 첫 구매 혜택 등)의 화면을 조회한다. " +
            "맨 위에 그 프로모션 배너(cornerStyle SQUARE, action null)를, 아래에 판매 중인 상품을 진열 순서대로 내린다. " +
            "무한 스크롤용 cursor 방식이다. 진행 중이 아니거나 대상 사용자가 아닌 프로모션이면 404다. " +
            "현재는 고정 프로모션 목록에서 찾아 반환한다.",
    )
    @ApiErrorCodes(shop = [ShopErrorCode.PROMOTION_NOT_FOUND])
    @GetMapping("/promotions/{promotionId}/products")
    fun getPromotionProducts(
        @CurrentUser user: AuthUser,
        @Parameter(description = "프로모션 ID (배너 링크 /shop/promotions/{promotionId}의 값)")
        @PathVariable("promotionId")
        promotionId: Long,
        @Parameter(description = "직전 응답의 nextCursor. 첫 요청에서는 생략한다.") @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "한 번에 조회할 개수 (1~50, 기본 20)")
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(50)
        size: Int,
    ): PromotionProductsResponse = shopService.getPromotionProducts(user.userId, promotionId, cursor, size)

    @Operation(
        summary = "상단 바 조회",
        description = "상점 상단 바의 코인 잔액과 장바구니에 담긴 상품 종류 수를 조회한다. 현재는 고정 응답을 반환한다.",
    )
    @GetMapping("/me")
    fun getMe(@CurrentUser user: AuthUser): ShopMeResponse = shopService.getMe(user.userId)
}
