package com.dandi.nyummy.security

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.security.filter.JwtAuthorizationFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerExceptionResolver

@Component
class SecurityExceptionHandler(
    @Qualifier("handlerExceptionResolver")
    private val resolver: HandlerExceptionResolver,
) : AuthenticationEntryPoint,
    AccessDeniedHandler {

    // 토큰이 없거나, 있는데 유효하지 않은 경우 -> UNAUTHORIZED
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val exception = request.getAttribute(JwtAuthorizationFilter.AUTH_EXCEPTION) as? BusinessException
            ?: BusinessException(AuthErrorCode.UNAUTHORIZED) // 토큰 자체가 없는 경우

        resolver.resolveException(request, response, null, exception)
    }

    // 인증은 됐는데 접근 불가 (존재하지 않는/매핑 안 된 경로) → FORBIDDEN
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        resolver.resolveException(request, response, null, BusinessException(AuthErrorCode.FORBIDDEN))
    }
}
