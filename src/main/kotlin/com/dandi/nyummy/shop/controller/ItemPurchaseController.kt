package com.dandi.nyummy.shop.controller

import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import com.dandi.nyummy.shop.dto.CreateItemPurchaseRequest
import com.dandi.nyummy.shop.dto.ItemPurchaseResponse
import com.dandi.nyummy.shop.service.ItemPurchaseService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "ItemPurchase", description = "상점 구매 API")
@RestController
@RequestMapping("/api/v1/shop/purchases")
class ItemPurchaseController(private val itemPurchaseService: ItemPurchaseService) {

    companion object {
        private const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }

    @Operation(
        summary = "구매",
        description = "장바구니에서 선택한 상품을 코인으로 산다. 산 아이템은 상품마다 우편 한 통으로 우편함에 가고, 산 상품은 장바구니에서 빠진다. " +
            "앱은 구매 버튼을 누를 때마다 Idempotency-Key(UUID)를 새로 만들고, 응답을 못 받아 재시도할 때만 같은 키를 다시 보낸다. " +
            "같은 키로 다시 오면 처음 결과를 그대로 돌려준다. " +
            "products는 장바구니에 담긴 상품·수량 그대로 보내고, purchasePrice는 화면에서 본 1개 결제가다. " +
            "차감할 코인은 서버가 계산하고, 화면 가격과 다르면 409 priceChanged다. " +
            "앞선 구매가 아직 처리 중이면 409 purchaseInProgress이고, 앱은 같은 키로 다시 보내지 않고 장바구니와 잔액을 다시 조회한다. " +
            "현재는 저장하지 않고 고정 상품·잔액으로 검증한 결과만 반환하며, 장바구니 일치(cartMismatch)와 처리 중(purchaseInProgress)은 아직 확인하지 않는다.",
    )
    @ApiErrorCodes(
        shop = [
            ShopErrorCode.PRODUCT_NOT_FOUND,
            ShopErrorCode.PRODUCT_NOT_FOR_SALE,
            ShopErrorCode.ALREADY_OWNED,
            ShopErrorCode.CART_MISMATCH,
            ShopErrorCode.PRICE_CHANGED,
            ShopErrorCode.INSUFFICIENT_BALANCE,
            ShopErrorCode.PURCHASE_IN_PROGRESS,
        ],
    )
    @PostMapping
    fun createItemPurchase(
        @CurrentUser user: AuthUser,
        @Parameter(description = "구매 버튼을 누를 때마다 앱이 만드는 UUID. 재시도할 때만 같은 값을 보낸다 (1~64자)")
        @RequestHeader(IDEMPOTENCY_KEY)
        @Size(min = 1, max = 64)
        idempotencyKey: String,
        @Valid @RequestBody request: CreateItemPurchaseRequest,
    ): ItemPurchaseResponse = itemPurchaseService.createItemPurchase(user.userId, idempotencyKey, request)
}
