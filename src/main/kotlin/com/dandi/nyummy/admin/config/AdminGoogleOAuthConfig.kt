package com.dandi.nyummy.admin.config

import com.dandi.nyummy.infra.oauth.google.AdminGoogleOAuthClient
import com.dandi.nyummy.infra.oauth.google.GoogleOAuthProperties
import com.dandi.nyummy.infra.oauth.google.createGoogleJwtDecoder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Clock

/**
 * 어드민 웹 Google 로그인 빈. 어드민 서비스(app.admin.enabled=true)에서만 만든다.
 *
 * 앱 디코더와 aud만 다르다. 앱 클라이언트 ID로 발급된 토큰은 받지 않으므로, 이 디코더를 통과한 토큰은
 * 어드민 웹에서 Google 로그인을 거친 것이다.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.admin", name = ["enabled"], havingValue = "true")
class AdminGoogleOAuthConfig {

    @Bean
    fun adminGoogleJwtDecoder(
        googleOAuthProperties: GoogleOAuthProperties,
        adminProperties: AdminProperties,
        clock: Clock,
    ): JwtDecoder {
        val clientId = adminProperties.googleClientId?.takeIf { it.isNotBlank() }
            ?: error("어드민 서비스에는 app.admin.google-client-id(ADMIN_GOOGLE_CLIENT_ID)가 필요합니다")

        return createGoogleJwtDecoder(googleOAuthProperties, clock, listOf(clientId))
    }

    @Bean
    fun adminGoogleOAuthClient(adminGoogleJwtDecoder: JwtDecoder): AdminGoogleOAuthClient =
        AdminGoogleOAuthClient(adminGoogleJwtDecoder)
}
