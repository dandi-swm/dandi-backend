package com.dandi.nyummy.admin.mapper

import com.dandi.nyummy.admin.dto.AdminProductItemResponse
import com.dandi.nyummy.admin.dto.AdminProductResponse
import com.dandi.nyummy.shop.entity.Product

fun Product.toAdminProductResponse(imageUrl: String, items: List<AdminProductItemResponse>): AdminProductResponse =
    AdminProductResponse(
        productId = this.id,
        name = this.name,
        category = this.category,
        representativeItemId = this.representativeItemId,
        imageUrl = imageUrl,
        price = this.price,
        isLimited = this.isLimited,
        saleStartsAt = this.saleStartsAt,
        saleEndsAt = this.saleEndsAt,
        purchaseLimit = this.purchaseLimit,
        salesCount = this.salesCount,
        items = items,
        createdAt = this.createdAt,
    )
