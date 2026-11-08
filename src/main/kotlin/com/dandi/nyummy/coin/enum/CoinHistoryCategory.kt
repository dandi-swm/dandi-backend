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

    /** 코인 충전 이벤트의 선물. 실제로 결제하지 않는다. sourceId = 패키지 번호, A 그룹 1~3·B 그룹 4~6(행의 id가 아니다) */
    COIN_GIFT,

    /** 상점 구매 환불. sourceId = item_purchase_refunds.id */
    ITEM_PURCHASE_REFUND,

    /** 리포트 구매. sourceId = report_purchases.id */
    REPORT_PURCHASE,

    /** 리포트 구매 환불(취소·생성 실패). sourceId = report_purchase_refunds.id */
    REPORT_PURCHASE_REFUND,
}
