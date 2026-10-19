package com.dandi.nyummy.config.openapi

import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.CatErrorCode
import com.dandi.nyummy.exception.errorcode.EmailErrorCode
import com.dandi.nyummy.exception.errorcode.InquiryErrorCode
import com.dandi.nyummy.exception.errorcode.InternalErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode

/**
 * API가 응답할 수 있는 비즈니스 에러코드를 Swagger 문서에 남긴다.
 *
 * [ErrorCodeOperationCustomizer]가 상태코드별로 묶어 응답 설명(코드 목록)과 예시를 만든다.
 * 어노테이션 인자로 인터페이스([com.dandi.nyummy.exception.errorcode.ErrorCode]) 배열을 받을 수 없어
 * 에러코드 enum마다 인자를 하나씩 둔다. 에러코드 enum을 추가하면 여기와 customizer에도 추가한다.
 *
 * 아래 코드는 자동으로 붙으므로 적지 않는다.
 * - 400 `api.common.invalidInputValue`: 요청 바디나 파라미터가 있는 API
 * - 401 `api.auth.unauthorized` · `api.auth.tokenExpired`: `@CurrentUser`로 인증 사용자를 받는 API
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ApiErrorCodes(
    val admin: Array<AdminErrorCode> = [],
    val auth: Array<AuthErrorCode> = [],
    val cat: Array<CatErrorCode> = [],
    val email: Array<EmailErrorCode> = [],
    val inquiry: Array<InquiryErrorCode> = [],
    val internal: Array<InternalErrorCode> = [],
    val meal: Array<MealErrorCode> = [],
    val s3: Array<S3ErrorCode> = [],
    val user: Array<UserErrorCode> = [],
)
