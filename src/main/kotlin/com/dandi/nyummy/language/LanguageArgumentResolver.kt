package com.dandi.nyummy.language

import com.dandi.nyummy.header.CommonHeader
import com.dandi.nyummy.language.enum.Language
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

// 헤더가 없어도 실패하지 않고 원본 언어로 채운다. 이전 앱 빌드·health check·Swagger는 X-Language를 보내지 않는다.
class LanguageArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentLanguage::class.java) &&
            parameter.parameterType == Language::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Language = Language.from(webRequest.getHeader(CommonHeader.LANGUAGE))
}
