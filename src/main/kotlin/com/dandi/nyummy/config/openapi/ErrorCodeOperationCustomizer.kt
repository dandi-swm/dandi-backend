package com.dandi.nyummy.config.openapi

import com.dandi.nyummy.exception.ErrorResponse
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.http.MediaType as HttpMediaType

/**
 * [ApiErrorCodes]와 공통 에러코드를 Swagger 응답으로 만든다.
 *
 * 상태코드 하나에 에러코드 여러 개가 묶이므로, 설명에는 코드 목록을 쓰고
 * 예시(examples)는 코드마다 하나씩 둬 Swagger UI의 Examples 선택 상자에서 고를 수 있게 한다.
 * 같은 상태코드에 `@ApiResponse` 설명이 이미 있으면 그 설명 뒤에 코드 목록을 붙인다.
 */
@Component
class ErrorCodeOperationCustomizer : OperationCustomizer {

    companion object {
        const val ERROR_RESPONSE_SCHEMA_NAME = "ErrorResponse"

        private val AUTH_REQUIRED_ERROR_CODES = listOf(AuthErrorCode.UNAUTHORIZED, AuthErrorCode.TOKEN_EXPIRED)
    }

    override fun customize(operation: Operation, handlerMethod: HandlerMethod): Operation {
        val errorCodes = buildList {
            if (operation.requestBody != null || !operation.parameters.isNullOrEmpty()) {
                add(CommonErrorCode.INVALID_INPUT_VALUE)
            }
            if (handlerMethod.methodParameters.any { it.hasParameterAnnotation(CurrentUser::class.java) }) {
                addAll(AUTH_REQUIRED_ERROR_CODES)
            }
            handlerMethod.getMethodAnnotation(ApiErrorCodes::class.java)?.let { addAll(convertErrorCodes(it)) }
        }.distinct()

        if (errorCodes.isEmpty()) return operation

        val responses = operation.responses ?: ApiResponses()
        errorCodes.groupBy { it.status.value().toString() }.forEach { (status, codes) ->
            responses.addApiResponse(status, createApiResponse(responses[status]?.description, codes))
        }
        operation.responses =
            ApiResponses().apply { responses.toSortedMap().forEach { (k, v) -> addApiResponse(k, v) } }
        return operation
    }

    private fun convertErrorCodes(annotation: ApiErrorCodes): List<ErrorCode> = with(annotation) {
        listOf(admin, auth, cat, email, inquiry, internal, meal, s3, user).flatMap { it.toList() }
    }

    private fun createApiResponse(existingDescription: String?, errorCodes: List<ErrorCode>): ApiResponse {
        val codeDescription = errorCodes.joinToString("\n") { "- `${it.code}`: ${it.message}" }
        val examples = errorCodes.associate {
            it.code to Example().summary(it.code).value(ErrorResponse.of(it))
        }

        return ApiResponse()
            .description(listOfNotNull(existingDescription, codeDescription).joinToString("\n\n"))
            .content(
                Content().addMediaType(
                    HttpMediaType.APPLICATION_JSON_VALUE,
                    MediaType()
                        .schema(Schema<ErrorResponse>().`$ref`(ERROR_RESPONSE_SCHEMA_NAME))
                        .examples(examples),
                ),
            )
    }
}
