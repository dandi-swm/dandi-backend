package com.dandi.nyummy.shop.enum

/**
 * 환불 정책에 어긋나는 이유. 무결성 문제와 달리 막지 않고, 운영자가 확인하면 예외로 환불한다.
 */
enum class RefundPolicyExceptionCategory {
    /** 구매한 지 환불 가능 기간이 지났다 */
    PERIOD_EXPIRED,

    /** 환불할 구성품을 장착한 적이 있다 */
    EQUIPPED,

    /** 돌려줄 코인이 0이다 */
    ZERO_COIN,
}
