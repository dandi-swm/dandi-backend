package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class S3ErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    UNSUPPORTED_CONTENT_TYPE(HttpStatus.BAD_REQUEST, "api.s3.unsupportedContentType", "지원하지 않는 파일 형식입니다."),
    FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "api.s3.fileSizeExceeded", "허용된 파일 크기를 초과했습니다."),
    INVALID_KEY(HttpStatus.BAD_REQUEST, "api.s3.invalidKey", "허용되지 않는 경로입니다."),
    OBJECT_NOT_FOUND(HttpStatus.BAD_REQUEST, "api.s3.objectNotFound", "업로드된 파일을 찾을 수 없습니다."),
    OBJECT_READ_FAILED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "api.s3.objectReadFailed",
        "서버 리소스를 읽을 수 없습니다.",
    ),
}
