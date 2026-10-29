package com.dandi.nyummy.shop.calculator

import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.entity.PromotionProduct

/**
 * 1개 결제가를 계산한다. 정가에 할인율을 적용하고 소수점 아래는 버린다(사용자에게 유리하게).
 *
 * 가격은 진행 중이고 사용자가 대상인 프로모션 중 할인율이 가장 큰 것 하나만 적용하므로, 할인율을 고르는 것은 부르는 쪽이 한다.
 *
 * @param listPrice 1개 정가
 * @param discountRate 할인율(0~100 정수 퍼센트). null이면 할인이 없다
 */
fun calculatePurchasePrice(listPrice: Int, discountRate: Int?): Int {
    if (discountRate == null) return listPrice
    return (listPrice.toLong() * (100 - discountRate) / 100).toInt()
}

/**
 * 상품마다 적용할 할인율을 고른다. 상품이 든 프로모션 중 할인율이 가장 큰 것 하나다.
 * 할인율이 없는 프로모션에만 든 상품과 어느 프로모션에도 없는 상품은 결과에 없다(할인 없음).
 *
 * @param promotions 진행 중이고 사용자가 대상인 프로모션
 * @param promotionProducts [promotions]의 진열 상품. 다른 프로모션의 행은 무시한다
 * @return productId → 할인율
 */
fun calculateMaxDiscountRates(promotions: List<Promotion>, promotionProducts: List<PromotionProduct>): Map<Long, Int> {
    val discountRates = promotions.mapNotNull { promotion ->
        promotion.discountRate?.let { promotion.id to it }
    }.toMap()

    return promotionProducts
        .mapNotNull { promotionProduct ->
            discountRates[promotionProduct.promotionId]?.let {
                promotionProduct.productId to it
            }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, rates) -> rates.max() }
}
