package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class CatErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {

    CAT_NOT_FOUND(HttpStatus.NOT_FOUND, "api.cat.notFound", "요청한 catId가 데이터베이스에 존재하지 않습니다."),
    CAT_ALREADY_EXISTS(HttpStatus.CONFLICT, "api.cat.alreadyExists", "이미 고양이가 존재합니다."),
    ANIMATION_METADATA_INVALID(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "api.cat.animationMetadataInvalid",
        "고양이 애니메이션 정보를 읽을 수 없습니다.",
    ),
}
