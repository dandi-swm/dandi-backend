package com.dandi.nyummy.security

import com.dandi.nyummy.security.filter.JwtAuthorizationFilter
import com.dandi.nyummy.security.jwt.TokenService
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
    private val tokenService: TokenService,
    private val securityExceptionHandler: SecurityExceptionHandler,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ERROR), permitAll)
                authorize("/api/v1/auth/logout", authenticated)
                authorize("/api/v1/auth/**", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/api/**", authenticated)
                authorize(anyRequest, denyAll)
            }
            exceptionHandling {
                authenticationEntryPoint = securityExceptionHandler
                accessDeniedHandler = securityExceptionHandler
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(JwtAuthorizationFilter(tokenService))
        }

        return http.build()
    }
}
