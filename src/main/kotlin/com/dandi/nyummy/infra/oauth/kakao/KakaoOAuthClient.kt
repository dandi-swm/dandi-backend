package com.dandi.nyummy.infra.oauth.kakao

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.infra.oauth.OAuthClient
import com.dandi.nyummy.infra.oauth.OAuthUserInfoResult
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException

class KakaoOAuthClient(private val jwtDecoder: JwtDecoder) : OAuthClient {

    companion object {
        private val logger = LoggerFactory.getLogger(KakaoOAuthClient::class.java)

        private const val CLAIM_NONCE = "nonce"
        private const val CLAIM_EMAIL = "email"
        private const val CLAIM_NICKNAME = "nickname"
    }

    override val provider: AuthProvider = AuthProvider.KAKAO

    override fun getUserInfo(idToken: String, nonce: String): OAuthUserInfoResult {
        val jwt = decode(idToken)

        if (jwt.getClaimAsString(CLAIM_NONCE) != nonce) {
            logger.warn("Kakao ID 토큰 nonce 불일치")
            throw BusinessException(AuthErrorCode.INVALID_OAUTH_TOKEN)
        }

        val providerUserId = jwt.subject
            ?: throw BusinessException(AuthErrorCode.INVALID_OAUTH_TOKEN)

        return OAuthUserInfoResult(
            provider = provider,
            providerUserId = providerUserId,
            email = jwt.getClaimAsString(CLAIM_EMAIL),
            nickname = jwt.getClaimAsString(CLAIM_NICKNAME),
        )
    }

    private fun decode(idToken: String): Jwt = try {
        jwtDecoder.decode(idToken)
    } catch (e: BadJwtException) {
        logger.warn("Kakao ID 토큰 검증 실패: {}", e.message)
        throw BusinessException(AuthErrorCode.INVALID_OAUTH_TOKEN)
    } catch (e: JwtException) {
        logger.error("Kakao 공개키 조회 실패", e)
        throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
    }
}
