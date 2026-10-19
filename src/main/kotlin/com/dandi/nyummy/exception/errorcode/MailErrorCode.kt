package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class MailErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    MAIL_NOT_FOUND(HttpStatus.NOT_FOUND, "api.mail.notFound", "요청한 mailId가 데이터베이스에 존재하지 않습니다."),
    ALREADY_CLAIMED(HttpStatus.CONFLICT, "api.mail.alreadyClaimed", "이미 받은 우편입니다."),
    MAIL_EXPIRED(HttpStatus.CONFLICT, "api.mail.expired", "만료된 우편입니다."),
}
