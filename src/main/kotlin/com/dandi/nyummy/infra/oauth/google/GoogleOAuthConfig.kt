package com.dandi.nyummy.infra.oauth.google

import com.dandi.nyummy.infra.oauth.OAuthClient
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.net.URI
import java.time.Clock
import java.time.Duration

private val CLOCK_SKEW: Duration = Duration.ofSeconds(60)

@Configuration
@EnableConfigurationProperties(GoogleOAuthProperties::class)
class GoogleOAuthConfig {

    /**
     * Google ID 토큰 디코더.
     *
     * Kakao와 달리 iss·aud를 단일 값이 아닌 목록으로 검증한다 — iss는 https 접두 유무 두 형태로 발급되고,
     * aud는 플랫폼별 클라이언트 ID(Android의 serverClientId, iOS 클라이언트 ID)가 될 수 있기 때문이다.
     */
    @Bean
    fun googleJwtDecoder(properties: GoogleOAuthProperties, clock: Clock): JwtDecoder =
        createGoogleJwtDecoder(properties, clock, properties.clientIds)

    @Bean
    fun googleOAuthClient(googleJwtDecoder: JwtDecoder): OAuthClient = GoogleOAuthClient(googleJwtDecoder)
}

/**
 * Google ID 토큰 디코더를 만든다. 서명·발급자·만료 검증은 공통이고, 허용하는 aud만 호출부가 정한다.
 *
 * 앱 로그인과 어드민 로그인이 같은 검증 규칙을 쓰도록 한 곳에 둔다.
 */
fun createGoogleJwtDecoder(properties: GoogleOAuthProperties, clock: Clock, audiences: List<String>): JwtDecoder {
    val resourceRetriever = DefaultResourceRetriever(
        properties.connectTimeout.toMillis().toInt(),
        properties.readTimeout.toMillis().toInt(),
    )

    val jwkSource = JWKSourceBuilder.create<SecurityContext>(URI(properties.jwkSetUri).toURL(), resourceRetriever)
        .cache(true)
        .refreshAheadCache(true)
        .rateLimited(true)
        .outageTolerantForever()
        .build()

    val jwtDecoder = NimbusJwtDecoder.withJwkSource(jwkSource)
        .jwsAlgorithm(SignatureAlgorithm.RS256)
        .build()

    jwtDecoder.setJwtValidator(
        DelegatingOAuth2TokenValidator(
            JwtTimestampValidator(CLOCK_SKEW).apply { setClock(clock) },
            JwtClaimValidator<String>(JwtClaimNames.ISS) { it in properties.issuers },
            JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { aud -> aud.any { it in audiences } },
        ),
    )

    return jwtDecoder
}
