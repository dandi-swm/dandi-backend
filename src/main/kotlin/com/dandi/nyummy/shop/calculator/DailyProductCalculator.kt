package com.dandi.nyummy.shop.calculator

import com.dandi.nyummy.shop.entity.Product
import kotlin.random.Random

/** 판매량이 낮은 상품 몫은 판매량 꼴찌부터 이 개수 안에서 고른다. */
private const val LOW_SALES_PRODUCT_COUNT = 10

/**
 * 오늘의 할인에 올릴 상품을 고른다. 하나는 판매량이 낮은 상품 중에서, 나머지는 전체에서 무작위로 고른다.
 *
 * 판매량이 같으면 무작위로 순위를 정하므로 모두 0이어도 늘 같은 상품이 낮은 쪽에 들지 않는다.
 * 어느 칸이 판매량이 낮은 상품인지 드러나지 않게 고른 상품의 순서도 섞는다.
 *
 * @param candidates 오늘의 할인에 올릴 수 있는 상품
 * @param count 고를 개수
 * @return 고른 상품. 후보가 [count]보다 적으면 후보 전체다
 */
fun pickDailyProducts(candidates: List<Product>, count: Int, random: Random): List<Product> {
    if (candidates.isEmpty()) return emptyList()

    val lowSalesProduct = candidates.shuffled(random)
        .sortedBy { it.salesCount }
        .take(LOW_SALES_PRODUCT_COUNT)
        .random(random)
    val otherProducts = (candidates - lowSalesProduct).shuffled(random).take(count - 1)

    return (otherProducts + lowSalesProduct).shuffled(random)
}
