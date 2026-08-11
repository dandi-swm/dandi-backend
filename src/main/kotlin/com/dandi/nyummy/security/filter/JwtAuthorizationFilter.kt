package com.dandi.nyummy.security.filter

import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.jwt.JwtProvider
import com.dandi.nyummy.security.jwt.TokenType
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthorizationFilter(private val jwtProvider: JwtProvider) : OncePerRequestFilter() {

    companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authorizationValue = request.getHeader(AUTHORIZATION_HEADER)

        if (authorizationValue != null) {
            if (!authorizationValue.startsWith("Bearer ")) {
                filterChain.doFilter(request, response)
                return
            }

            val authorization = authorizationValue.substring(7)

            try {
                val userId = jwtProvider.getUserId(authorization, TokenType.ACCESS)

                val authentication = UsernamePasswordAuthenticationToken.authenticated(
                    AuthUser(userId),
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER")),
                )
                SecurityContextHolder.setContext(
                    SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication },
                )
            } catch (e: JwtException) {
            }
        }

        filterChain.doFilter(request, response)
    }
}
