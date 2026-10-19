package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class AdminErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    NOT_ADMIN(HttpStatus.FORBIDDEN, "api.admin.notAdmin", "어드민으로 등록되지 않았거나 비활성화된 계정입니다."),
}
