package com.dandi.nyummy.admin.mapper

import com.dandi.nyummy.admin.dto.AdminItemPurchaseLineResponse
import com.dandi.nyummy.admin.dto.AdminItemPurchaseProductResponse
import com.dandi.nyummy.admin.dto.AdminItemPurchaseRefundResponse
import com.dandi.nyummy.admin.dto.AdminItemPurchaseResponse
import com.dandi.nyummy.shop.entity.ItemPurchase
import com.dandi.nyummy.shop.entity.ItemPurchaseLine
import com.dandi.nyummy.shop.entity.ItemPurchaseProduct
import com.dandi.nyummy.shop.entity.ItemPurchaseRefund
import com.dandi.nyummy.shop.enum.ItemPurchaseLineStatus

fun ItemPurchase.toAdminItemPurchaseResponse(products: List<AdminItemPurchaseProductResponse>) =
    AdminItemPurchaseResponse(
        itemPurchaseId = id,
        purchasePrice = purchasePrice,
        createdAt = createdAt,
        products = products,
    )

fun ItemPurchaseProduct.toAdminItemPurchaseProductResponse(
    productName: String,
    lines: List<AdminItemPurchaseLineResponse>,
    refunds: List<AdminItemPurchaseRefundResponse>,
) = AdminItemPurchaseProductResponse(
    itemPurchaseProductId = id,
    productName = productName,
    listPrice = listPrice,
    purchasePrice = purchasePrice,
    lines = lines,
    refunds = refunds,
)

fun ItemPurchaseLine.toAdminItemPurchaseLineResponse(
    itemName: String,
    status: ItemPurchaseLineStatus,
    isEquipped: Boolean,
) = AdminItemPurchaseLineResponse(
    itemPurchaseLineId = id,
    itemName = itemName,
    listPrice = listPrice,
    status = status,
    isEquipped = isEquipped,
)

fun ItemPurchaseRefund.toAdminItemPurchaseRefundResponse() = AdminItemPurchaseRefundResponse(
    itemPurchaseRefundId = id,
    coinQuantity = coinQuantity,
    reason = reason,
    createdAt = createdAt,
)
