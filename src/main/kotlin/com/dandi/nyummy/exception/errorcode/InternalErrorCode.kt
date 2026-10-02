package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class InternalErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    INVALID_BATCH_KEY(
        HttpStatus.UNAUTHORIZED,
        "api.internal.invalidBatchKey",
        "유효하지 않은 배치 비밀키입니다.",
    ),
}
