@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.shop.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

/**
 * 장바구니 전체. 조회, 수량 변경, 선택 변경, 빼기가 모두 이 모양을 돌려주고, 앱은 받을 때마다 목록과 결제 정보를 다시 그린다.
 *
 * @property selectedItemCount 선택된 상품 종류 수("전체 선택 3/4"의 3)
 */
data class CartResponse(
    val cartItems: List<CartItemResponse>,
    val selectedItemCount: Int,
    val summary: CartSummaryResponse,
)

/**
 * @property maxQuantity 담을 수 있는 최대 수량. 1이면 앱이 수량 버튼을 숨기고, 0이면 구매 불가다
 * @property isSelected 선택 여부. 구매 불가 항목은 항상 false
 * @property canPurchase false면 판매 기간 밖, 보유한 방 배경, 1인 구매 한도 도달 중 하나
 * @property purchasePrice 지금 결제할 1개 가격(할인 적용)
 * @property unavailableReason 구매 불가일 때 서버가 만든 문구
 */
data class CartItemResponse(
    val shopItem: ShopItemResponse,
    val quantity: Int,
    val maxQuantity: Int,
    val isSelected: Boolean,
    val canPurchase: Boolean,
    val purchasePrice: Int,
    val unavailableReason: String?,
)

/**
 * 결제 정보. 선택됐고 구매 가능한 항목만 센다.
 *
 * @property quantity 수량 합("4개 구매하기")
 * @property listPrice 정가 × 수량의 합(상품 금액)
 * @property discountPrice 할인된 코인(listPrice − purchasePrice)
 * @property purchasePrice 결제할 코인
 * @property balance 내 코인
 * @property balanceAfter 구매 후 남는 코인. 부족하면 음수
 */
data class CartSummaryResponse(
    val quantity: Int,
    val listPrice: Int,
    val discountPrice: Int,
    val purchasePrice: Int,
    val balance: Int,
    val balanceAfter: Int,
)

data class CreateCartItemRequest(
    val productId: Long,

    @field:Min(value = 1, message = "quantity는 1 이상이어야 합니다.")
    val quantity: Int,
)

/**
 * @property quantity 담은 뒤 이 상품의 수량
 * @property cartItemCount 장바구니에 담긴 상품 종류 수(상단 배지)
 */
data class CreatedCartItemResponse(val productId: Long, val quantity: Int, val cartItemCount: Int)

data class UpdateCartItemQuantityRequest(
    @field:Min(value = 1, message = "quantity는 1 이상이어야 합니다. 빼려면 장바구니 빼기 API를 쓴다.")
    val quantity: Int,
)

/**
 * @property productIds 선택할 상품 목록. 이 목록으로 선택 상태를 맞추고, 빈 목록이면 전체 해제다
 */
data class UpdateCartSelectionRequest(
    @field:Size(max = 30, message = "productIds는 30개 이하여야 합니다.")
    val productIds: List<Long>,
)
