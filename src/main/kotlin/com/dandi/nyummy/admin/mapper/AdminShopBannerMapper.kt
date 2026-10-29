package com.dandi.nyummy.admin.mapper

import com.dandi.nyummy.admin.dto.AdminShopBannerResponse
import com.dandi.nyummy.shop.entity.ShopBanner

fun ShopBanner.toAdminShopBannerResponse(promotionName: String, imageUrl: String): AdminShopBannerResponse =
    AdminShopBannerResponse(
        bannerId = this.id,
        promotionId = this.promotionId,
        promotionName = promotionName,
        imageUrl = imageUrl,
        title = this.title,
        description = this.description,
        titleColor = this.titleColor,
        descriptionColor = this.descriptionColor,
        displayOrder = this.displayOrder,
        startsAt = this.startsAt,
        endsAt = this.endsAt,
        adminId = this.adminId,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
    )
