package com.dandi.nyummy.filter

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.InternalErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

class InternalApiKeyFilter(private val batchKey: String) : OncePerRequestFilter() {

    companion object {
        const val BATCH_KEY_HEADER = "X-Internal-Batch-Key"
        const val BATCH_AUTHORITY = "ROLE_BATCH"
        private const val INTERNAL_PATH_PREFIX = "/api/v1/internal/"
        private val log = LoggerFactory.getLogger(InternalApiKeyFilter::class.java)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith(INTERNAL_PATH_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val provided = request.getHeader(BATCH_KEY_HEADER)

        if (provided != null && matches(provided)) {
            val authentication = UsernamePasswordAuthenticationToken(
                "internal-batch",
                null,
                listOf(SimpleGrantedAuthority(BATCH_AUTHORITY)),
            )
            SecurityContextHolder.getContextHolderStrategy().context =
                SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        } else {
            log.warn("배치 비밀 키 검증 실패: uri={}, remoteAddr={}", request.requestURI, request.remoteAddr)
            request.setAttribute(
                JwtAuthorizationFilter.AUTH_EXCEPTION,
                BusinessException(InternalErrorCode.INVALID_BATCH_KEY),
            )
        }

        filterChain.doFilter(request, response)
    }

    private fun matches(provided: String): Boolean =
        MessageDigest.isEqual(batchKey.toByteArray(), provided.toByteArray())
}
