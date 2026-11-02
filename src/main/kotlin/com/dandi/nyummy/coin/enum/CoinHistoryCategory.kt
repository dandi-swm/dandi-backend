package com.dandi.nyummy.coin.enum

/**
 * 코인 변동의 종류. [com.dandi.nyummy.coin.entity.CoinHistory.sourceId]가 어느 테이블의 행인지도 정한다.
 */
enum class CoinHistoryCategory {
    /** 우편 받기. sourceId = mails.id */
    MAIL_CLAIM,

    /** 상점 구매. sourceId = item_purchases.id */
    ROOM_ITEM_PURCHASE,

    /** 인앱 결제로 코인 구매 */
    IN_APP_PURCHASE,
}
