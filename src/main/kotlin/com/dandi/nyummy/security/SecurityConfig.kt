package com.dandi.nyummy.security

import com.dandi.nyummy.admin.config.AdminProperties
import com.dandi.nyummy.filter.InternalApiKeyFilter
import com.dandi.nyummy.filter.JwtAuthorizationFilter
import com.dandi.nyummy.internal.config.InternalProperties
import com.dandi.nyummy.security.jwt.JwtService
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class SecurityConfig(
    private val jwtService: JwtService,
    private val securityExceptionHandler: SecurityExceptionHandler,
) {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        internalProperties: InternalProperties,
        adminProperties: AdminProperties,
    ): SecurityFilterChain {
        http {
            cors { configurationSource = createAdminCorsConfigurationSource(adminProperties) }
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ERROR), permitAll)
                authorize("/actuator/health", permitAll)
                authorize("/api/v1/internal/**", hasAuthority(InternalApiKeyFilter.BATCH_AUTHORITY))
                authorize("/api/v1/admin/auth/login", permitAll)
                authorize("/api/v1/admin/**", hasAuthority(AuthAdmin.AUTHORITY))
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

/**
 * 어드민 웹(브라우저)이 어드민 API를 부를 수 있게 허용한다. 앱 API는 브라우저에서 부르지 않으므로 열지 않는다.
 *
 * 어드민은 httpOnly 쿠키로 인증하므로 allowCredentials를 켠다. credentials를 켜면 출처에 와일드카드를 쓸 수 없어
 * 정확한 주소만 허용한다.
 */
private fun createAdminCorsConfigurationSource(adminProperties: AdminProperties): CorsConfigurationSource {
    val configuration = CorsConfiguration().apply {
        allowedOrigins = adminProperties.allowedOrigins
        allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        allowedHeaders = listOf(HttpHeaders.CONTENT_TYPE)
        allowCredentials = true
        maxAge = 3600
    }

    return UrlBasedCorsConfigurationSource().apply {
        registerCorsConfiguration("/api/v1/admin/**", configuration)
    }
}
