package com.dandi.nyummy.infra.oauth.apple

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.infra.oauth.OAuthClient
import com.dandi.nyummy.infra.oauth.OAuthUserInfoResult
import com.fasterxml.jackson.annotation.JsonProperty
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date

class AppleOAuthClient(
    private val jwtDecoder: JwtDecoder,
    private val restClient: RestClient,
    private val properties: AppleOAuthProperties,
    private val clock: Clock,
) : OAuthClient {

    companion object {
        private val logger = LoggerFactory.getLogger(AppleOAuthClient::class.java)

        private const val CLAIM_NONCE = "nonce"

        // code가 틀렸거나, 만료됐거나(5분), 이미 쓴 경우 Apple이 돌려주는 오류. 이 밖의 4xx는 서버 설정 문제다.
        private const val ERROR_INVALID_GRANT = "invalid_grant"

        private val PEM_BOUNDARY = Regex("-----[A-Z ]+-----")
        private val WHITESPACE = Regex("\\s")
    }

    override val provider: AuthProvider = AuthProvider.APPLE

    /**
     * Apple OIDC ID 토큰을 검증한다. 서명, 발급자, 대상, 만료는 [jwtDecoder]가, nonce는 여기서 대조한다.
     *
     * nonce는 앱이 Apple 로그인 요청에 넣은 값을 그대로 받는다. 앱이 해시해서 넣었다면 해시값이 온다.
     * nonce가 없으면 토큰을 이 앱 세션에 묶을 수 없으므로 검증 전에 거절한다. 다른 경로에서 얻은 ID 토큰 주입을 막는다.
     */
    override fun getUserInfo(token: String, nonce: String?): OAuthUserInfoResult {
        val expectedNonce = nonce
            ?: throw BusinessException(AuthErrorCode.OAUTH_NONCE_REQUIRED)

        val jwt = decodeIdToken(token)

        if (jwt.getClaimAsString(CLAIM_NONCE) != expectedNonce) {
            logger.warn("Apple ID 토큰 nonce 불일치")
            throw BusinessException(AuthErrorCode.INVALID_OAUTH_TOKEN)
        }

        val providerUserId = jwt.subject
            ?: throw BusinessException(AuthErrorCode.INVALID_OAUTH_TOKEN)

        return OAuthUserInfoResult(
            provider = provider,
            providerUserId = providerUserId,
        )
    }

    /**
     * 탈퇴하는 사용자와 이 앱의 Apple 연결을 끊는다. App Store 심사 지침 5.1.1(v)가 요구한다.
     *
     * Apple 토큰을 저장하지 않으므로, 앱이 탈퇴 직전에 Apple 인증을 다시 띄워 받은 [authorizationCode]로
     * 토큰을 새로 받고 곧바로 취소한다. 받은 ID 토큰의 sub가 [providerUserId]와 다르면 다른 Apple 계정의 code이므로
     * 취소하지 않고 거절한다. 그대로 두면 남의 연결을 끊거나, 탈퇴하는 사용자의 연결은 남는다.
     *
     * 어느 단계든 실패하면 예외를 던진다. 호출부는 이 메서드가 끝난 뒤에 탈퇴를 처리하므로, 실패하면 아무것도 바뀌지 않는다.
     *
     * @throws BusinessException [AuthErrorCode.INVALID_OAUTH_AUTHORIZATION_CODE] code가 틀렸거나 만료, 재사용됐거나 다른 계정의 것인 경우
     * @throws BusinessException [AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE] Apple과 통신할 수 없거나 서버 설정(키 등)이 잘못된 경우
     */
    fun withdrawUser(authorizationCode: String, providerUserId: String) {
        val clientSecret = createClientSecret()

        val appleToken = createAppleToken(authorizationCode, clientSecret)

        val subject = try {
            jwtDecoder.decode(appleToken.idToken).subject
        } catch (e: BadJwtException) {
            logger.warn("Apple 토큰 응답의 ID 토큰 검증 실패: {}", e.message)
            throw BusinessException(AuthErrorCode.INVALID_OAUTH_AUTHORIZATION_CODE)
        } catch (e: JwtException) {
            logger.error("Apple 공개키 조회 실패", e)
            throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        }

        if (subject != providerUserId) {
            logger.warn("탈퇴 요청의 Apple 계정이 사용자와 다름")
            throw BusinessException(AuthErrorCode.INVALID_OAUTH_AUTHORIZATION_CODE)
        }

        // refresh_token은 code 교환에서 항상 오지만, 없으면 access_token을 취소한다. 둘 다 같은 연결을 끊는다.
        val (token, tokenTypeHint) = appleToken.refreshToken?.let { it to "refresh_token" }
            ?: (appleToken.accessToken to "access_token")

        deleteAppleToken(token, tokenTypeHint, clientSecret)
    }

    private fun decodeIdToken(token: String): Jwt = try {
        jwtDecoder.decode(token)
    } catch (e: BadJwtException) {
        logger.warn("Apple ID 토큰 검증 실패: {}", e.message)
        throw BusinessException(AuthErrorCode.INVALID_OAUTH_TOKEN)
    } catch (e: JwtException) {
        logger.error("Apple 공개키 조회 실패", e)
        throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
    }

    private fun createAppleToken(authorizationCode: String, clientSecret: String): AppleTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", properties.clientId)
            add("client_secret", clientSecret)
            add("code", authorizationCode)
            add("grant_type", "authorization_code")
        }

        return try {
            restClient.post()
                .uri(properties.tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(AppleTokenResponse::class.java)
                ?: throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        } catch (e: HttpClientErrorException) {
            if (e.responseBodyAsString.contains(ERROR_INVALID_GRANT)) {
                logger.warn("Apple authorization code 교환 거절: {}", ERROR_INVALID_GRANT)
                throw BusinessException(AuthErrorCode.INVALID_OAUTH_AUTHORIZATION_CODE)
            }
            logger.error(
                "Apple authorization code 교환 실패. client secret 설정 확인 필요: status={}, body={}",
                e.statusCode,
                e.responseBodyAsString,
            )
            throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        } catch (e: RestClientException) {
            logger.error("Apple 토큰 요청 실패", e)
            throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        }
    }

    private fun deleteAppleToken(token: String, tokenTypeHint: String, clientSecret: String) {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", properties.clientId)
            add("client_secret", clientSecret)
            add("token", token)
            add("token_type_hint", tokenTypeHint)
        }

        try {
            restClient.post()
                .uri(properties.revokeUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity()
        } catch (e: HttpClientErrorException) {
            logger.error("Apple 토큰 취소 거절: status={}, body={}", e.statusCode, e.responseBodyAsString)
            throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        } catch (e: RestClientException) {
            logger.error("Apple 토큰 취소 요청 실패", e)
            throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        }
    }

    /**
     * Apple에 보낼 client secret을 만든다. .p8 키로 ES256 서명한 JWT다.
     *
     * 탈퇴는 드물어서 캐시하지 않고 매번 짧은 만료로 만든다. 키는 여기서 처음 읽는다. 기동할 때 읽으면 키가 없는
     * 환경(Worker 등)이 뜨지 못한다.
     */
    private fun createClientSecret(): String {
        val issuedAt = Instant.now(clock)

        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .keyID(properties.keyId)
            .build()

        val claims = JWTClaimsSet.Builder()
            .issuer(properties.teamId)
            .subject(properties.clientId)
            .audience(properties.issuer)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plus(properties.clientSecretTimeToLive)))
            .build()

        return try {
            SignedJWT(header, claims)
                .apply { sign(ECDSASigner(decodePrivateKey())) }
                .serialize()
        } catch (e: GeneralSecurityException) {
            logger.error("Apple 개인 키를 읽을 수 없음. APPLE_PRIVATE_KEY 설정 확인 필요", e)
            throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        } catch (e: IllegalArgumentException) {
            logger.error("Apple 개인 키가 base64가 아님. APPLE_PRIVATE_KEY 설정 확인 필요", e)
            throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        } catch (e: JOSEException) {
            logger.error("Apple client secret 서명 실패", e)
            throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        }
    }

    private fun decodePrivateKey(): ECPrivateKey {
        val base64 = properties.privateKey
            .replace(PEM_BOUNDARY, "")
            .replace(WHITESPACE, "")

        val keySpec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))

        return KeyFactory.getInstance("EC").generatePrivate(keySpec) as ECPrivateKey
    }
}

/**
 * Apple 토큰 요청 응답. 토큰 값은 로그에 남기지 않는다.
 */
data class AppleTokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("refresh_token") val refreshToken: String?,
    @JsonProperty("id_token") val idToken: String,
)
