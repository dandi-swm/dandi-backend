package com.dandi.nyummy.shop.calculator

/**
 * 이번 환불로 돌려줄 코인을 계산한다. 구매 상품 1개의 일부나 전부를 환불할 때 쓴다.
 *
 * 세트를 일부만 환불하면 묶음 할인이 깨지므로, 남은 구성품은 정가로 산 것으로 다시 계산한다.
 * 그래서 지금까지 돌려줄 수 있는 코인은 결제가에서 남은 구성품 정가 합을 뺀 값이고, 남은 정가가 결제가보다 크면 0이다.
 * 여기서 이전 환불액을 빼므로, 나눠서 환불해도 합계는 결제가를 넘지 않고 모두 환불하면 결제가와 같다.
 *
 * @param purchasePrice 구매 상품 1개의 결제가
 * @param remainingListPrice 이번 환불 뒤에 남는 구성품의 구매 시점 정가 합
 * @param refundedCoinQuantity 이 구매 상품에서 이전 환불로 돌려준 코인 합
 */
fun calculateRefundCoinQuantity(purchasePrice: Int, remainingListPrice: Int, refundedCoinQuantity: Int): Int =
    (purchasePrice - remainingListPrice).coerceAtLeast(0) - refundedCoinQuantity
