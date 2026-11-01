package com.dandi.nyummy.shop.dto

/** 사용자가 상품 하나를 산 개수. 구매 상품은 1개당 한 행이라 행 수를 센다. */
data class PurchasedProductCountResult(val productId: Long, val purchasedCount: Long)
