package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class CatErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {

    CAT_NOT_FOUND(HttpStatus.NOT_FOUND, "api.cat.notFound", "요청한 catId가 데이터베이스에 존재하지 않습니다."),
}
