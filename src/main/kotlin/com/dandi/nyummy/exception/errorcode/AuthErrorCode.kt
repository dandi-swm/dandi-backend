package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class AuthErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "api.auth.invalid-credentials", "이메일 또는 비밀번호가 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "api.auth.unauthorized", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "api.auth.forbidden", "해당 요청에 대한 권한이 없습니다."),
}
