@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.admin.dto

import com.dandi.nyummy.room.entity.OwnedRoomItem
import com.dandi.nyummy.shop.enum.RefundPolicyExceptionCategory
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/**
 * @property itemPurchaseLineIds 환불할 구성품. 모두 같은 구매 상품이어야 한다
 * @property reason 환불 이유
 * @property expectedCoinQuantity 화면에서 본 돌려줄 코인. 다시 계산한 금액과 다르면 거부한다
 * @property isPolicyException 정책에 어긋나도 예외로 환불하는지. 어긋나는데 false면 거부한다
 */
data class CreateItemPurchaseRefundRequest(
    @field:NotEmpty(message = "환불할 구성품은 하나 이상이어야 합니다.")
    val itemPurchaseLineIds: List<Long>,

    @field:NotBlank(message = "환불 이유는 필수입니다.")
    @field:Size(max = 255, message = "환불 이유는 255자 이하여야 합니다.")
    val reason: String,

    val expectedCoinQuantity: Int,

    val isPolicyException: Boolean = false,
)

/**
 * @property coinQuantity 이번 환불로 돌려줄 코인
 * @property policyExceptions 이번 환불이 정책에 어긋나는 이유. 비어 있으면 정책 안의 환불이다
 * @property lines 구성품별로 정책에 어긋나는 이유. 요청한 순서대로 준다
 */
data class ItemPurchaseRefundPreviewResponse(
    val coinQuantity: Int,
    val policyExceptions: List<RefundPolicyExceptionCategory>,
    val lines: List<ItemPurchaseRefundLinePreviewResponse>,
)

/**
 * @property policyExceptions 이 구성품이 정책에 어긋나는 이유. 돌려줄 코인은 환불 한 건에 하나라 ZERO_COIN은 여기 없다
 */
data class ItemPurchaseRefundLinePreviewResponse(
    val itemPurchaseLineId: Long,
    val policyExceptions: List<RefundPolicyExceptionCategory>,
)

/**
 * @property coinQuantity 돌려준 코인
 * @property balance 돌려준 뒤 사용자의 잔액
 */
data class ItemPurchaseRefundResponse(val itemPurchaseRefundId: Long, val coinQuantity: Int, val balance: Int)

/**
 * @property ownedRoomItems 환불할 구성품 중 우편을 받아 보유 아이템이 된 것. 아직 받지 않은 구성품은 이 목록에 없다
 */
data class ItemPurchaseRefundCalculationResult(
    val coinQuantity: Int,
    val policyExceptions: List<RefundPolicyExceptionCategory>,
    val lines: List<ItemPurchaseRefundLinePreviewResponse>,
    val ownedRoomItems: List<OwnedRoomItem>,
)
