package com.dandi.nyummy.infra.oauth.apple

import com.dandi.nyummy.infra.oauth.OAuthClient
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
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
import org.springframework.web.client.RestClient
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

    /**
     * Apple 로그인 클라이언트. 로그인 검증 외에 탈퇴할 때 Apple 토큰 취소도 맡아서, 탈퇴 쪽이 이 타입으로 주입받게
     * [OAuthClient]가 아닌 구현 타입으로 등록한다. [OAuthClient] 목록으로 주입받는 로그인 쪽에도 그대로 들어간다.
     *
     * RestClient를 빈으로 노출하지 않는 이유는 NaverOAuthConfig와 같다. 타입만으로 주입받는 곳이 있어 빈이 둘이 되면 모호해진다.
     */
    @Bean
    fun appleOAuthClient(
        appleJwtDecoder: JwtDecoder,
        properties: AppleOAuthProperties,
        clock: Clock,
    ): AppleOAuthClient {
        val settings = HttpClientSettings.defaults()
            .withConnectTimeout(properties.connectTimeout)
            .withReadTimeout(properties.readTimeout)

        val restClient = RestClient.builder()
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .build()

        return AppleOAuthClient(appleJwtDecoder, restClient, properties, clock)
    }
}
