package com.dandi.nyummy.infra.oauth.google

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException

/**
 * 어드민 웹의 Google 로그인 ID 토큰을 검증해 이메일을 꺼낸다.
 *
 * 앱 로그인(GoogleOAuthClient)과 달리 OAuthClient를 구현하지 않는다. OAuthService가 OAuthClient 빈을
 * provider로 묶어 쓰므로, 같은 GOOGLE provider로 등록되면 앱 Google 로그인이 이 클라이언트로 바뀐다.
 *
 * nonce는 검증하지 않는다. aud가 어드민 웹 전용 클라이언트라 다른 앱에서 얻은 토큰은 이미 거절되고,
 * 어드민 웹용 토큰을 가로챈 공격자라면 nonce도 함께 얻는다.
 */
class AdminGoogleOAuthClient(private val jwtDecoder: JwtDecoder) {

    companion object {
        private val logger = LoggerFactory.getLogger(AdminGoogleOAuthClient::class.java)

        private const val CLAIM_EMAIL = "email"
        private const val CLAIM_EMAIL_VERIFIED = "email_verified"
    }

    /**
     * ID 토큰을 검증하고 Google이 소유를 확인한 이메일을 반환한다. 대소문자는 그대로 둔다.
     */
    fun getEmail(idToken: String): String {
        val jwt = decodeIdToken(idToken)

        if (jwt.getClaimAsBoolean(CLAIM_EMAIL_VERIFIED) != true) {
            logger.warn("어드민 Google ID 토큰의 이메일이 확인되지 않음")
            throw BusinessException(AuthErrorCode.INVALID_OAUTH_TOKEN)
        }

        return jwt.getClaimAsString(CLAIM_EMAIL)
            ?: throw BusinessException(AuthErrorCode.INVALID_OAUTH_TOKEN)
    }

    private fun decodeIdToken(idToken: String): Jwt = try {
        jwtDecoder.decode(idToken)
    } catch (e: BadJwtException) {
        logger.warn("어드민 Google ID 토큰 검증 실패: {}", e.message)
        throw BusinessException(AuthErrorCode.INVALID_OAUTH_TOKEN)
    } catch (e: JwtException) {
        logger.error("Google 공개키 조회 실패", e)
        throw BusinessException(AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
    }
}
