package com.dandi.nyummy.report.enum

/**
 * 리포트·구매·환불·코인 내역·생성 작업 사이의 불일치 종류. 대조 배치가 찾는다.
 */
enum class ReportMismatchCategory {
    /** 유료로 산 리포트가 취소·실패로 끝났는데 환불 행이 없다 */
    MISSING_REFUND,

    /** 환불 행이 있는데 리포트가 취소·실패가 아니거나 무료 구매다 */
    UNEXPECTED_REFUND,

    /** 환불한 코인이 구매 가격과 다르다 */
    REFUND_QUANTITY_MISMATCH,

    /** 구매의 코인 차감 내역이 기대한 수(유료 1건, 무료 0건)와 다르다. 2건 이상이면 이중 차감이다 */
    PURCHASE_COIN_HISTORY_MISMATCH,

    /** 환불 행의 코인 지급 내역이 1건이 아니다. 2건 이상이면 이중 환불이다 */
    REFUND_COIN_HISTORY_MISMATCH,

    /** 끝난(완료·취소·실패) 리포트에 생성 작업이 남아 있다 */
    ORPHAN_JOB,

    /** 끝나지 않은 리포트에 생성 작업이 없어 영원히 대기한다 */
    MISSING_JOB,

    /** 생성이 정한 시간 안에 끝나지 않았다(Worker가 멈췄거나 계속 미뤄지는 중) */
    OVERDUE_GENERATION,

    /** 완료된 리포트에 제목·뷰·내용·완료 시각 중 빈 값이 있다 */
    INCOMPLETE_COMPLETED,
}
