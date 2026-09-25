package com.dandi.nyummy.infra.oauth.kakao

import com.dandi.nyummy.infra.oauth.OAuthClient
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.net.URI
import java.time.Clock
import java.time.Duration

@Configuration
@EnableConfigurationProperties(KakaoOAuthProperties::class)
class KakaoOAuthConfig {

    companion object {
        private val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
    }

    @Bean
    fun kakaoJwtDecoder(properties: KakaoOAuthProperties, clock: Clock): JwtDecoder {
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
                JwtAudienceValidator(properties.appKey),
            ),
        )

        return jwtDecoder
    }

    @Bean
    fun kakaoOAuthClient(kakaoJwtDecoder: JwtDecoder): OAuthClient = KakaoOAuthClient(kakaoJwtDecoder)
}
