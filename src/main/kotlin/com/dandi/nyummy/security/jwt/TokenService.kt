package com.dandi.nyummy.security.jwt

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.auth.enum.AuthPurpose
import com.dandi.nyummy.auth.repository.TokenInvalidationRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.security.AuthUser
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*

@Service
class TokenService(
    private val jwtProvider: JwtProvider,
    private val tokenInvalidationRepository: TokenInvalidationRepository,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TokenService::class.java)
    }

    fun getAuthentication(token: String): Authentication {
        val (userId, issuedAt) = jwtProvider.getAccessTokenClaims(token)

        val invalidatedAt = try {
            tokenInvalidationRepository.getInvalidatedAt(userId)
        } catch (e: DataAccessException) {
            logger.warn("토큰 무효화 조회 실패: userId={}", userId, e)
            null
        }

        if (invalidatedAt != null && issuedAt.isBefore(invalidatedAt)) {
            throw BusinessException(AuthErrorCode.UNAUTHORIZED)
        }

        return UsernamePasswordAuthenticationToken.authenticated(AuthUser(userId, token), null, emptyList())
    }

    fun createTokenPair(userId: Long): Pair<String, String> {
        val access = jwtProvider.createAccessToken(userId)
        val refresh = jwtProvider.createRefreshToken(userId)
        return Pair(access, refresh)
    }

    fun getEmailAndPurpose(token: String, type: TokenType): Pair<String, AuthPurpose> = try {
        Pair(jwtProvider.getEmail(token, type), jwtProvider.getPurpose(token, type))
    } catch (e: ExpiredJwtException) {
        throw BusinessException(AuthErrorCode.EMAIL_VERIFICATION_EXPIRED)
    } catch (e: JwtException) {
        throw BusinessException(AuthErrorCode.UNAUTHORIZED)
    }

    fun createEmailChallengeToken(email: String, purpose: AuthPurpose): String =
        jwtProvider.createEmailChallengeToken(email, purpose)

    fun createEmailVerifiedToken(email: String, purpose: AuthPurpose): String =
        jwtProvider.createEmailVerifiedToken(email, purpose)

    /**
     * 소셜 로그인 검증을 마쳤지만 아직 가입하지 않은 사용자를 위한 oauthVerifiedToken을 발급한다.
     *
     * emailVerifiedToken과 같은 역할이다 — 검증 완료와 회원가입 사이(프로필 입력 구간)를 잇는 다리.
     * 검증 결과(provider, providerUserId, email)를 클레임으로 실어 가입 시 재검증 없이 사용자를 만든다.
     */
    fun createOAuthVerifiedToken(provider: AuthProvider, providerUserId: String, email: String?): String =
        jwtProvider.createOAuthVerifiedToken(provider, providerUserId, email)

    fun getOAuthVerifiedClaims(token: String): OAuthVerifiedClaims = try {
        jwtProvider.getOAuthVerifiedClaims(token)
    } catch (e: ExpiredJwtException) {
        throw BusinessException(AuthErrorCode.OAUTH_VERIFICATION_EXPIRED)
    } catch (e: JwtException) {
        throw BusinessException(AuthErrorCode.UNAUTHORIZED)
    }

    fun getPurpose(token: String, type: TokenType): AuthPurpose = jwtProvider.getPurpose(token, type)

    fun getUserId(token: String, type: TokenType): Long = jwtProvider.getUserId(token, type)

    fun getEmail(token: String, type: TokenType): String = jwtProvider.getEmail(token, type)

    fun getExpiration(token: String, type: TokenType): Date = jwtProvider.getExpiration(token, type)
}

data class AccessTokenClaims(val userId: Long, val issuedAt: Instant)

data class OAuthVerifiedClaims(val provider: AuthProvider, val providerUserId: String, val email: String?)
