package com.dandi.nyummy.security

import com.dandi.nyummy.filter.InternalApiKeyFilter
import com.dandi.nyummy.filter.JwtAuthorizationFilter
import com.dandi.nyummy.internal.config.InternalProperties
import com.dandi.nyummy.security.jwt.JwtService
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher

@Configuration
class SecurityConfig(
    private val jwtService: JwtService,
    private val securityExceptionHandler: SecurityExceptionHandler,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity, internalProperties: InternalProperties): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ERROR), permitAll)
                authorize("/actuator/health", permitAll)
                authorize("/api/v1/internal/**", hasAuthority(InternalApiKeyFilter.BATCH_AUTHORITY))
                authorize("/api/v1/auth/logout", hasAuthority(AuthUser.AUTHORITY))
                authorize("/api/v1/auth/**", permitAll) // 로그인 하기 전에 부르는 API --> USER 권한 요구 X
                authorize("/swagger-ui/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/api/**", hasAuthority(AuthUser.AUTHORITY))
                authorize(anyRequest, denyAll)
            }
            exceptionHandling {
                authenticationEntryPoint = securityExceptionHandler
                accessDeniedHandler = securityExceptionHandler
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(JwtAuthorizationFilter(jwtService))
            addFilterBefore<UsernamePasswordAuthenticationFilter>(
                InternalApiKeyFilter(internalProperties.batchKey),
            )
        }

        return http.build()
    }
}
