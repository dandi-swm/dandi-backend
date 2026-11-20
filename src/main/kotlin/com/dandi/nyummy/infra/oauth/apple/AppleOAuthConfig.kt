package com.dandi.nyummy.infra.oauth.apple

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
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.net.URI
import java.time.Clock
import java.time.Duration

@Configuration
@EnableConfigurationProperties(AppleOAuthProperties::class)
class AppleOAuthConfig {

    companion object {
        private val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
    }

    /**
     * Apple ID 토큰 디코더.
     *
     * aud는 목록으로 검증한다. iOS 앱이 받은 토큰의 aud는 번들 ID이고, 웹 흐름(Android 등)으로 받은 토큰의 aud는
     * Services ID라서, 다른 플랫폼을 붙일 때 설정에 값만 더하면 되게 한다.
     */
    @Bean
    fun appleJwtDecoder(properties: AppleOAuthProperties, clock: Clock): JwtDecoder {
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
                JwtIssuerValidator(properties.issuer),
                JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { aud -> aud.any { it in properties.clientIds } },
            ),
        )

        return jwtDecoder
    }

    @Bean
    fun appleOAuthClient(appleJwtDecoder: JwtDecoder): OAuthClient = AppleOAuthClient(appleJwtDecoder)
}
