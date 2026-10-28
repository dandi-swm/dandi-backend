@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.admin.dto

import com.dandi.nyummy.shop.enum.PromotionCategory
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * 프로모션 만들기 요청.
 *
 * @property category 대상. 만든 뒤에는 바꾸지 않는다
 * @property discountRate 할인율(1~99 정수 퍼센트). null이면 할인 없이 상품만 묶는다
 * @property endsAt null이면 종료일이 없다
 * @property productIds 진열할 상품. 이 순서대로 진열한다
 */
data class CreatePromotionRequest(
    @field:NotBlank(message = "프로모션 이름은 필수입니다.")
    @field:Size(max = 100, message = "프로모션 이름은 100자 이하여야 합니다.")
    val name: String,

    val category: PromotionCategory,

    @field:Min(value = 1, message = "할인율은 1~99여야 합니다. 할인이 없으면 비워 주세요.")
    @field:Max(value = 99, message = "할인율은 1~99여야 합니다. 할인이 없으면 비워 주세요.")
    val discountRate: Int?,

    val startsAt: Instant,

    val endsAt: Instant?,

    @field:Size(max = 100, message = "프로모션 상품은 100개 이하여야 합니다.")
    val productIds: List<Long>,
)

/**
 * 프로모션 수정(PUT) 요청. 보낸 값으로 통째로 바꾸므로 모두 보낸다. 대상과 진열 상품은 바꾸지 않는다.
 * Kotlin DTO는 키가 없는 것과 null을 구분하지 못해 [discountRate]·[endsAt]을 빼면 null(할인 없음·종료일 없음)로 받는다.
 *
 * @property endsAt 프로모션을 끝낼 때는 지금 시각을 보낸다
 */
data class UpdatePromotionRequest(
    @field:NotBlank(message = "프로모션 이름은 필수입니다.")
    @field:Size(max = 100, message = "프로모션 이름은 100자 이하여야 합니다.")
    val name: String,

    @field:Min(value = 1, message = "할인율은 1~99여야 합니다. 할인이 없으면 비워 주세요.")
    @field:Max(value = 99, message = "할인율은 1~99여야 합니다. 할인이 없으면 비워 주세요.")
    val discountRate: Int?,

    val startsAt: Instant,

    val endsAt: Instant?,
)

/**
 * 프로모션 진열 상품 교체(PUT) 요청. 보낸 목록으로 통째로 바꾼다.
 *
 * @property productIds 이 순서대로 진열한다. 비우면 진열 상품이 없어진다
 */
data class UpdatePromotionProductsRequest(
    @field:Size(max = 100, message = "프로모션 상품은 100개 이하여야 합니다.")
    val productIds: List<Long>,
)

data class AdminPromotionsResponse(val promotions: List<AdminPromotionResponse>)

/**
 * @property products 진열 상품. 진열 순서대로
 */
data class AdminPromotionResponse(
    val promotionId: Long,
    val name: String,
    val category: PromotionCategory,
    val discountRate: Int?,
    val startsAt: Instant,
    val endsAt: Instant?,
    val products: List<AdminPromotionProductResponse>,
    val createdAt: Instant,
)

/**
 * 프로모션에 진열한 상품 하나.
 *
 * @property imageUrl 대표 구성품의 미리보기 URL
 * @property listPrice 1개 정가
 * @property purchasePrice 이 프로모션의 할인율만 적용한 1개 결제가. 사용자에게는 대상인 프로모션 중 할인율이 가장 큰 것이 적용된다
 */
data class AdminPromotionProductResponse(
    val productId: Long,
    val name: String,
    val imageUrl: String,
    val listPrice: Int,
    val purchasePrice: Int,
    val saleStartsAt: Instant,
    val saleEndsAt: Instant?,
)
