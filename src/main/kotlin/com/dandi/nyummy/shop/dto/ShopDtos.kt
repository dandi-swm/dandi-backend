@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.shop.dto

data class ShopItemsResponse(val items: List<ShopItemResponse>, val nextCursor: Long?, val hasNext: Boolean)

/**
 * 배너를 누르면 여는 프로모션 화면. 맨 위에 그 프로모션 배너를 직각 모서리로 그리고, 아래에 상품 목록을 그린다.
 *
 * @property banner 화면 맨 위 배너. 추천 탭과 같은 배너를 cornerStyle SQUARE, action null로 내린다
 */
data class PromotionProductsResponse(
    val banner: ShopBannerResponse,
    val items: List<ShopItemResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

/**
 * 상점 상단 바.
 *
 * @property cartItemCount 장바구니에 담긴 상품 종류 수
 */
data class ShopMeResponse(val balance: Int, val cartItemCount: Int)
