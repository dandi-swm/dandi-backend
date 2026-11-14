package com.dandi.nyummy.shop.dto

/**
 * 구매 상품 1개의 상품 이름. 구매 상품은 1개당 한 행이라 같은 상품을 여러 개 사면 여러 행이다.
 *
 * @property nameTranslations 상품 이름의 번역([com.dandi.nyummy.shop.entity.Product.nameTranslations])
 */
data class ItemPurchaseProductNameResult(
    val itemPurchaseId: Long,
    val name: String,
    val nameTranslations: Map<String, String>?,
)
