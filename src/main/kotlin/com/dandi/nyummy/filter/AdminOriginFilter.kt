package com.dandi.nyummy.filter

import com.dandi.nyummy.admin.config.AdminPaths
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 어드민 API의 쓰기 요청이 어드민 웹에서 왔는지 Origin 헤더로 확인한다. 쿠키 인증의 CSRF 방어다.
 *
 * SameSite=Strict는 다른 사이트의 요청만 막고, 같은 사이트인 형제 서브도메인(link.nyummy.co.kr 등)의 요청에는
 * 쿠키가 그대로 붙는다. 브라우저는 POST·PUT·PATCH·DELETE에 항상 Origin을 붙이므로, 허용 출처가 아니거나
 * Origin이 없으면 거절한다. 읽기 요청은 상태를 바꾸지 않고, 응답은 CORS가 다른 출처에 보여주지 않으므로 보지 않는다.
 *
 * 인가 단계 이후에 거절하면 이미 인증된 어드민 요청도 통과시키게 되므로, 필터에서 직접 403으로 응답한다.
 */
class AdminOriginFilter(
    private val allowedOrigins: List<String>,
    private val accessDeniedHandler: AccessDeniedHandler,
) : OncePerRequestFilter() {

    companion object {
        private const val ADMIN_PATH_PREFIX = "${AdminPaths.PREFIX}/"
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
        private val log = LoggerFactory.getLogger(AdminOriginFilter::class.java)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith(ADMIN_PATH_PREFIX) || request.method in SAFE_METHODS

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val origin = request.getHeader(HttpHeaders.ORIGIN)

        if (origin == null || origin !in allowedOrigins) {
            log.warn("어드민 요청 출처 거절: uri={}, origin={}", request.requestURI, origin)
            accessDeniedHandler.handle(request, response, AccessDeniedException("허용되지 않은 출처입니다: $origin"))
            return
        }

        filterChain.doFilter(request, response)
    }
}
