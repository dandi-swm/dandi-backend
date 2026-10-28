package com.dandi.nyummy.admin.mapper

import com.dandi.nyummy.admin.dto.AdminPromotionProductResponse
import com.dandi.nyummy.admin.dto.AdminPromotionResponse
import com.dandi.nyummy.shop.calculator.calculatePurchasePrice
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.Promotion

fun Promotion.toAdminPromotionResponse(products: List<AdminPromotionProductResponse>): AdminPromotionResponse =
    AdminPromotionResponse(
        promotionId = this.id,
        name = this.name,
        category = this.category,
        discountRate = this.discountRate,
        startsAt = this.startsAt,
        endsAt = this.endsAt,
        products = products,
        createdAt = this.createdAt,
    )

fun Product.toAdminPromotionProductResponse(imageUrl: String, discountRate: Int?): AdminPromotionProductResponse =
    AdminPromotionProductResponse(
        productId = this.id,
        name = this.name,
        imageUrl = imageUrl,
        listPrice = this.price,
        purchasePrice = calculatePurchasePrice(this.price, discountRate),
        saleStartsAt = this.saleStartsAt,
        saleEndsAt = this.saleEndsAt,
    )
