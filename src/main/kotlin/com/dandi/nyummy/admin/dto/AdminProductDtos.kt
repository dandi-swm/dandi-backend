@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.admin.dto

import com.dandi.nyummy.room.enum.RoomItemCategory
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * 상품 만들기 요청. 구성품이 하나면 단품, 여럿이면 세트다.
 *
 * @property category 단품은 구성품의 분류와 같아야 한다. 세트는 운영자가 정하고, 방 배경은 단품으로만 판다
 * @property representativeItemId 카드에 그릴 그림. [items] 중 하나여야 한다
 * @property price 1개 정가. 만든 뒤에는 바꾸지 않는다. 단품은 구성품 정가와 같고, 세트는 구성품 정가 합 이하다
 * @property saleStartsAt 미래 시각이면 예약 등록이다
 * @property saleEndsAt null이면 종료일이 없다
 * @property purchaseLimit 1인 구매 한도. null이면 무제한이다
 */
data class CreateProductRequest(
    @field:NotBlank(message = "상품 이름은 필수입니다.")
    @field:Size(max = 100, message = "상품 이름은 100자 이하여야 합니다.")
    val name: String,

    val category: RoomItemCategory,

    val representativeItemId: Long,

    @field:PositiveOrZero(message = "정가는 0 이상이어야 합니다.")
    val price: Int,

    val isLimited: Boolean,

    val saleStartsAt: Instant,

    val saleEndsAt: Instant?,

    @field:Positive(message = "1인 구매 한도는 1 이상이어야 합니다.")
    val purchaseLimit: Int?,

    @field:Size(min = 1, max = 20, message = "구성품은 1~20개여야 합니다.")
    @field:Valid
    val items: List<CreateProductItemRequest>,
)

/**
 * @property listPrice 구성품 정가. 세트를 일부만 환불할 때 남는 구성품을 이 값으로 계산한다
 */
data class CreateProductItemRequest(
    val itemId: Long,

    @field:PositiveOrZero(message = "구성품 정가는 0 이상이어야 합니다.")
    val listPrice: Int,
)

/**
 * 상품 수정(PUT) 요청. 보낸 값으로 통째로 바꾸므로 모두 보낸다. 정가·분류·구성품·대표 구성품은 바꾸지 않는다.
 * Kotlin DTO는 키가 없는 것과 null을 구분하지 못해 [saleEndsAt]·[purchaseLimit]을 빼면 null(종료일 없음·무제한)로 받는다.
 *
 * @property saleEndsAt 판매 중지는 지금 시각을 보낸다
 */
data class UpdateProductRequest(
    @field:NotBlank(message = "상품 이름은 필수입니다.")
    @field:Size(max = 100, message = "상품 이름은 100자 이하여야 합니다.")
    val name: String,

    val isLimited: Boolean,

    val saleStartsAt: Instant,

    val saleEndsAt: Instant?,

    @field:Positive(message = "1인 구매 한도는 1 이상이어야 합니다.")
    val purchaseLimit: Int?,
)

data class AdminProductsResponse(val products: List<AdminProductResponse>)

/**
 * @property imageUrl 대표 구성품의 미리보기 URL
 * @property salesCount 최근 판매량. Internal 배치가 갱신한다
 * @property items 구성품. itemId 오름차순
 */
data class AdminProductResponse(
    val productId: Long,
    val name: String,
    val category: RoomItemCategory,
    val representativeItemId: Long,
    val imageUrl: String,
    val price: Int,
    val isLimited: Boolean,
    val saleStartsAt: Instant,
    val saleEndsAt: Instant?,
    val purchaseLimit: Int?,
    val salesCount: Int,
    val items: List<AdminProductItemResponse>,
    val createdAt: Instant,
)

/**
 * @property imageUrl 앱과 같은 규칙(CDN 주소 + `/items/{itemId}.png`)으로 만든 미리보기 URL
 */
data class AdminProductItemResponse(val itemId: Long, val name: String, val listPrice: Int, val imageUrl: String)
