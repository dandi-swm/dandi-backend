package com.dandi.nyummy.filter

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.security.AdminTokenCookie
import com.dandi.nyummy.security.jwt.JwtService
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthorizationFilter(private val jwtService: JwtService) : OncePerRequestFilter() {

    companion object {
        const val AUTH_EXCEPTION = "authException"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = extractToken(request)

        if (token != null) {
            try {
                val authentication = jwtService.getAuthentication(token)
                SecurityContextHolder.getContextHolderStrategy().context =
                    SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
            } catch (e: ExpiredJwtException) {
                request.setAttribute(AUTH_EXCEPTION, BusinessException(AuthErrorCode.TOKEN_EXPIRED))
            } catch (e: JwtException) {
                request.setAttribute(AUTH_EXCEPTION, BusinessException(AuthErrorCode.UNAUTHORIZED))
            } catch (e: BusinessException) {
                request.setAttribute(AUTH_EXCEPTION, e)
            }
        }

        filterChain.doFilter(request, response)
    }

    /**
     * 앱은 Authorization 헤더로, 어드민 웹은 httpOnly 쿠키로 토큰을 보낸다. 둘 다 있으면 헤더를 쓴다.
     */
    private fun extractToken(request: HttpServletRequest): String? = request.getHeader(HttpHeaders.AUTHORIZATION)
        ?.takeIf { it.startsWith("Bearer ") }
        ?.substring(7)
        ?: request.cookies?.firstOrNull { it.name == AdminTokenCookie.NAME }?.value
}
