package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class UserErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "api.user.notFound", "존재하지 않는 유저입니다."),
    PROFILE_NOT_FOUND(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "api.user.profileNotFound",
        "사용자 프로필을 찾을 수 없습니다.",
    ),
    PASSWORD_NOT_SET(HttpStatus.BAD_REQUEST, "api.user.passwordNotSet", "소셜 로그인 계정은 비밀번호를 변경할 수 없습니다."),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "api.user.passwordMismatch", "현재 비밀번호가 일치하지 않습니다."),
}
