package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class UserErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "api.user.notFound", "존재하지 않는 유저입니다."),
}
