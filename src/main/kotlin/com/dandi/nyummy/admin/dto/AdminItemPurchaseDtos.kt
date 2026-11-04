@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.admin.dto

import com.dandi.nyummy.shop.enum.ItemPurchaseLineStatus
import java.time.Instant

data class AdminItemPurchasesResponse(
    val itemPurchases: List<AdminItemPurchaseResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

data class AdminItemPurchaseResponse(
    val itemPurchaseId: Long,
    val purchasePrice: Int,
    val createdAt: Instant,
    val products: List<AdminItemPurchaseProductResponse>,
)

data class AdminItemPurchaseProductResponse(
    val itemPurchaseProductId: Long,
    val productName: String,
    val listPrice: Int,
    val purchasePrice: Int,
    val lines: List<AdminItemPurchaseLineResponse>,
    val refunds: List<AdminItemPurchaseRefundResponse>,
)

/**
 * @property isEquipped 지금 마이룸에 장착 중인지. 받기 전이거나 환불했으면 false다
 */
data class AdminItemPurchaseLineResponse(
    val itemPurchaseLineId: Long,
    val itemName: String,
    val listPrice: Int,
    val status: ItemPurchaseLineStatus,
    val isEquipped: Boolean,
)

/**
 * @property coinQuantity 이 환불로 돌려준 코인
 * @property reason 운영자가 적은 환불 이유
 */
data class AdminItemPurchaseRefundResponse(
    val itemPurchaseRefundId: Long,
    val coinQuantity: Int,
    val reason: String,
    val createdAt: Instant,
)
