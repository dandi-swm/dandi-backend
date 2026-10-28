package com.dandi.nyummy.shop.calculator

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
