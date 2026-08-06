package com.dandi.nyummy.security

import com.dandi.nyummy.security.filter.JwtAuthorizationFilter
import com.dandi.nyummy.security.jwt.JwtProvider
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher

@Configuration
class SecurityConfig(
    private val authErrorResponseWriter: AuthErrorResponseWriter,
    private val jwtProvider: JwtProvider,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            exceptionHandling {
                authenticationEntryPoint = authErrorResponseWriter
                accessDeniedHandler = authErrorResponseWriter
            }
            authorizeHttpRequests {
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ERROR), permitAll)
                authorize("/api/v1/auth/**", permitAll)
                authorize("/api/**", authenticated)
                authorize(anyRequest, denyAll)
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(JwtAuthorizationFilter(jwtProvider))
        }

        return http.build()
    }
}
