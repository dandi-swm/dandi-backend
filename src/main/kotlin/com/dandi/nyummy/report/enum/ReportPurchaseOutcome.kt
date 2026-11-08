package com.dandi.nyummy.report.enum

import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.ReportErrorCode

/**
 * 리포트 구매 시도의 결과. 거부 사유는 응답한 에러 코드와 짝을 이룬다.
 */
enum class ReportPurchaseOutcome {
    /** 구매를 만들었다 */
    SUCCEEDED,

    /** 같은 Idempotency-Key의 처음 결과를 다시 돌려줬다 */
    REPLAYED,

    /** 같은 Idempotency-Key로 다른 주·가격을 보냈다 */
    IDEMPOTENCY_KEY_REUSED,

    INVALID_WEEK,
    NOT_ENOUGH_MEALS,
    DUPLICATE_WEEK,
    PRICE_CHANGED,
    INSUFFICIENT_BALANCE,

    /** 코인 행 잠금을 제시간에 얻지 못했거나 교착으로 롤백되었다 */
    PURCHASE_IN_PROGRESS,
    ;

    companion object {
        /** 거부 사유의 에러 코드에 맞는 결과. 구매 시도의 거부 사유가 아니면 null이다 */
        fun from(errorCode: ErrorCode): ReportPurchaseOutcome? = when (errorCode) {
            ReportErrorCode.IDEMPOTENCY_KEY_REUSED -> IDEMPOTENCY_KEY_REUSED
            ReportErrorCode.INVALID_WEEK -> INVALID_WEEK
            ReportErrorCode.NOT_ENOUGH_MEALS -> NOT_ENOUGH_MEALS
            ReportErrorCode.DUPLICATE_WEEK -> DUPLICATE_WEEK
            ReportErrorCode.PRICE_CHANGED -> PRICE_CHANGED
            ReportErrorCode.INSUFFICIENT_BALANCE -> INSUFFICIENT_BALANCE
            else -> null
        }
    }
}
