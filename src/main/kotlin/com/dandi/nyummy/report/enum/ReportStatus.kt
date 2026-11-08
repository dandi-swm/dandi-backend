package com.dandi.nyummy.report.enum

/**
 * 리포트의 상태. WAITING → GENERATING → COMPLETED로 가고, 끝나지 않은 리포트는 CANCELED나 FAILED로 끝난다.
 */
enum class ReportStatus {
    /** 구매 완료, 생성 대기 */
    WAITING,

    /** Worker가 생성 중 */
    GENERATING,

    /** 생성 완료. 취소·환불할 수 없다 */
    COMPLETED,

    /** 사용자가 생성 전·중에 취소했다. 유료였으면 환불되었다 */
    CANCELED,

    /** 생성에 실패했다. 유료였으면 환불되었다 */
    FAILED,
    ;

    companion object {
        /** 생성이 끝나지 않은 상태. 사용자가 취소할 수 있다 */
        val IN_PROGRESS_STATUSES = setOf(WAITING, GENERATING)

        /**
         * 취소·실패로 끝나지 않은 상태. 같은 주 중복 구매와 첫 무료 판정에서 센다.
         * 취소·실패한 리포트는 세지 않아 같은 주를 다시 살 수 있고, 무료로 산 리포트였으면 무료 기회가 다시 생긴다.
         */
        val ACTIVE_STATUSES = setOf(WAITING, GENERATING, COMPLETED)
    }
}
