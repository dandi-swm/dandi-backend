package com.dandi.nyummy.shop.enum

/**
 * 구매 구성품이 지금 어떤 상태인지. 저장하지 않고 환불 기록과 보유 아이템으로 판단한다.
 */
enum class ItemPurchaseLineStatus {
    /** 우편을 아직 받지 않았다 */
    NOT_CLAIMED,

    /** 우편을 받아 보유 아이템이 됐다 */
    CLAIMED,

    /** 환불했다 */
    REFUNDED,
}
