package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class ReportErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "api.report.notFound", "요청한 reportId가 데이터베이스에 존재하지 않습니다."),
    INVALID_WEEK(HttpStatus.BAD_REQUEST, "api.report.invalidWeek", "startDate가 일요일이 아니거나 아직 끝나지 않은 주입니다."),
    NOT_ENOUGH_MEALS(HttpStatus.BAD_REQUEST, "api.report.notEnoughMeals", "그 주에 분석이 완료된 식사가 부족합니다."),
    DUPLICATE_WEEK(HttpStatus.CONFLICT, "api.report.duplicateWeek", "같은 주의 리포트가 이미 있습니다."),
    PRICE_CHANGED(HttpStatus.CONFLICT, "api.report.priceChanged", "리포트 가격이 바뀌었습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "api.report.insufficientBalance", "코인이 부족합니다."),
    IDEMPOTENCY_KEY_REUSED(
        HttpStatus.CONFLICT,
        "api.report.idempotencyKeyReused",
        "같은 Idempotency-Key로 다른 구매를 요청했습니다.",
    ),
    PURCHASE_IN_PROGRESS(HttpStatus.CONFLICT, "api.report.purchaseInProgress", "처리 중인 구매가 있습니다."),
    NOT_CANCELABLE(HttpStatus.CONFLICT, "api.report.notCancelable", "완료되었거나 생성에 실패한 리포트는 취소할 수 없습니다."),
}
