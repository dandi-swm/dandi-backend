package com.dandi.nyummy.shop.controller

import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import com.dandi.nyummy.shop.dto.CartResponse
import com.dandi.nyummy.shop.dto.CreateCartItemRequest
import com.dandi.nyummy.shop.dto.CreatedCartItemResponse
import com.dandi.nyummy.shop.dto.UpdateCartItemQuantityRequest
import com.dandi.nyummy.shop.dto.UpdateCartSelectionRequest
import com.dandi.nyummy.shop.service.CartService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Cart", description = "장바구니 조회 · 담기 · 수량 변경 · 선택 변경 · 빼기 API")
@RestController
@RequestMapping("/api/v1/shop/cart")
class CartController(private val cartService: CartService) {

    companion object {
        private const val STUB_NOTICE = "현재는 저장하지 않고, 고정 장바구니에 요청을 반영한 결과만 반환한다."
    }

    @Operation(
        summary = "장바구니 조회",
        description = "장바구니 항목과 결제 정보를 조회한다. 항목은 담은 시각 최신순이다. " +
            "summary는 선택됐고 구매 가능한 항목만 합친다. maxQuantity는 상품당 10개(방 배경 1개)와 " +
            "장바구니 전체 30개 중 남은 수량으로 정해지고, 구매 불가 항목은 0이다. 현재는 고정 응답을 반환한다.",
    )
    @GetMapping
    fun getCart(@CurrentUser user: AuthUser): CartResponse = cartService.getCart(user.userId)

    @Operation(
        summary = "장바구니 담기",
        description = "상품 상세 시트에서 상품을 quantity개 더 담는다. 이미 담긴 상품이면 기존 수량에 더하고, 담은 상품은 선택 상태가 된다. " +
            "cartItemCount는 상단 배지에 쓸 장바구니 상품 종류 수다. " + STUB_NOTICE,
    )
    @ApiErrorCodes(
        shop = [
            ShopErrorCode.PRODUCT_NOT_FOUND,
            ShopErrorCode.PRODUCT_NOT_FOR_SALE,
            ShopErrorCode.ALREADY_OWNED,
            ShopErrorCode.CART_QUANTITY_EXCEEDED,
            ShopErrorCode.CART_FULL,
        ],
    )
    @PostMapping("/items")
    fun createCartItem(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: CreateCartItemRequest,
    ): CreatedCartItemResponse = cartService.createCartItem(user.userId, request)

    @Operation(
        summary = "장바구니 수량 변경",
        description = "장바구니 화면의 −/+로 상품 수량을 이 값으로 맞추고 장바구니 전체를 반환한다. " +
            "누를 때마다 보내며, 응답이 보낸 순서대로 오지 않을 수 있으니 앱은 마지막으로 보낸 요청의 응답만 반영한다. " +
            "빼려면 장바구니 빼기 API를 쓴다. " + STUB_NOTICE,
    )
    @ApiErrorCodes(
        shop = [ShopErrorCode.CART_ITEM_NOT_FOUND, ShopErrorCode.CART_QUANTITY_EXCEEDED, ShopErrorCode.CART_FULL],
    )
    @PutMapping("/items/{productId}")
    fun updateCartItemQuantity(
        @CurrentUser user: AuthUser,
        @Parameter(description = "상품 ID") @PathVariable("productId") productId: Long,
        @Valid @RequestBody request: UpdateCartItemQuantityRequest,
    ): CartResponse = cartService.updateCartItemQuantity(user.userId, productId, request)

    @Operation(
        summary = "장바구니 선택 변경",
        description = "선택된 상품 목록을 요청 목록으로 맞추고 장바구니 전체를 반환한다. 전체 선택은 구매 가능한 모든 id, " +
            "전체 해제는 빈 목록이다. 장바구니에 없거나 구매 불가인 id는 무시한다. " + STUB_NOTICE,
    )
    @PutMapping("/selection")
    fun updateCartSelection(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: UpdateCartSelectionRequest,
    ): CartResponse = cartService.updateCartSelection(user.userId, request)

    @Operation(
        summary = "장바구니 빼기",
        description = "상품을 장바구니에서 빼고 장바구니 전체를 반환한다. 전체 삭제는 앱이 모든 id를 보낸다. " +
            "productIds는 쉼표 구분(117,121)과 반복(productIds=117&productIds=121) 모두 받는다. " +
            "장바구니에 없는 id는 무시한다. " + STUB_NOTICE,
    )
    @DeleteMapping("/items")
    fun deleteCartItems(
        @CurrentUser user: AuthUser,
        @Parameter(description = "뺄 상품 ID 목록 (1~30개)")
        @RequestParam("productIds")
        @Size(min = 1, max = 30)
        productIds: List<Long>,
    ): CartResponse = cartService.deleteCartItems(user.userId, productIds)
}
