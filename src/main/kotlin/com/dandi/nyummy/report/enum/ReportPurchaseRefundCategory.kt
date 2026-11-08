package com.dandi.nyummy.report.enum

/**
 * 리포트 구매를 왜 환불했는지.
 */
enum class ReportPurchaseRefundCategory {
    /** 사용자가 생성 전·중에 취소했다 */
    CANCELED,

    /** 생성에 실패했다 */
    FAILED,
}
