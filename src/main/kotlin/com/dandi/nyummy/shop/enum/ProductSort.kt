package com.dandi.nyummy.shop.enum

/**
 * 상품 목록 정렬 기준. 값이 같으면 productId 내림차순이다.
 */
enum class ProductSort {
    /** 신상품순. 판매 시작 시각이 늦은 순 */
    NEW,

    /** 판매량순 */
    POPULAR,

    /** 결제가 낮은 순(할인 중이면 할인가) */
    PRICE_ASC,

    /** 결제가 높은 순(할인 중이면 할인가) */
    PRICE_DESC,
}
