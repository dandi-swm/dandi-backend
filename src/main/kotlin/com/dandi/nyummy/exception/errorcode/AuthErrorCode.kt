package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class AuthErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "api.auth.invalidCredentials", "이메일 또는 비밀번호가 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "api.auth.unauthorized", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "api.auth.forbidden", "해당 요청에 대한 권한이 없습니다."),
    MAIL_RESEND_TOO_EARLY(HttpStatus.TOO_MANY_REQUESTS, "api.auth.mailResendTooEarly", "인증 코드를 발송한 지 5분이 지나지 않았습니다."),
    MAIL_NOT_FOUND(HttpStatus.NOT_FOUND, "api.auth.mailNotFound", "해당 이메일로 발송된 인증 코드가 없습니다."),
    MAIL_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "api.auth.mailCodeExpired", "인증 코드 유효 시간이 지났습니다. 코드를 재발송 받으세요."),
    MAIL_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "api.auth.mailCodeMismatch", "인증 코드가 일치하지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "api.auth.invalidRefreshToken", "유효하지 않은 리프레시 토큰입니다."),
}
