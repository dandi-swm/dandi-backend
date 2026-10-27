@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.shop.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

/**
 * @property products 장바구니에서 선택한 상품 목록. 같은 상품을 두 번 넣지 않는다
 */
data class CreateItemPurchaseRequest(
    @field:Size(min = 1, max = 30, message = "products는 1개 이상 30개 이하여야 합니다.")
    @field:Valid
    val products: List<ItemPurchaseProductRequest>,
)

/**
 * @property quantity 장바구니에 담긴 수량
 * @property purchasePrice 화면에서 본 1개 결제가. 서버는 이 값으로 차감하지 않고, 지금 가격과 같은지만 확인한다
 */
data class ItemPurchaseProductRequest(
    val productId: Long,

    @field:Min(value = 1, message = "quantity는 1 이상이어야 합니다.")
    val quantity: Int,

    @field:Min(value = 0, message = "purchasePrice는 0 이상이어야 합니다.")
    val purchasePrice: Int,
)

/**
 * 구매 결과. 산 아이템은 우편함으로 가고, 앱은 이 값으로 상단 바와 장바구니 배지를 고친다.
 *
 * @property itemPurchaseId 구매 id. 같은 Idempotency-Key로 다시 보내면 같은 구매가 온다
 * @property quantity 산 상품 수량 합
 * @property purchasePrice 차감한 코인
 * @property balance 구매 후 잔액
 * @property cartItemCount 산 상품을 뺀 뒤 장바구니에 남은 상품 종류 수(상단 배지)
 */
data class ItemPurchaseResponse(
    val itemPurchaseId: Long,
    val quantity: Int,
    val purchasePrice: Int,
    val balance: Int,
    val cartItemCount: Int,
)
