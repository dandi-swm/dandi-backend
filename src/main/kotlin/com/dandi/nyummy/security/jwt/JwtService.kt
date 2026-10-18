package com.dandi.nyummy.security.jwt

import com.dandi.nyummy.admin.repository.AdminRepository
import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.auth.enum.AuthPurpose
import com.dandi.nyummy.auth.repository.TokenInvalidationRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.security.AuthAdmin
import com.dandi.nyummy.security.AuthUser
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class JwtService(
    private val jwtProvider: JwtProvider,
    private val tokenInvalidationRepository: TokenInvalidationRepository,
    private val adminRepository: AdminRepository,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(JwtService::class.java)
    }

    fun getAuthentication(token: String): Authentication =
        when (val claims = jwtProvider.getAuthenticationClaims(token)) {
            is AccessTokenClaims -> getUserAuthentication(claims, token)

            // 어드민 인증은 아직 붙이지 않았다. 어드민 토큰으로는 어떤 경로도 인증되지 않는다.
            is AdminTokenClaims -> throw BusinessException(AuthErrorCode.UNAUTHORIZED)
        }

    private fun getUserAuthentication(claims: AccessTokenClaims, token: String): Authentication {
        val (userId, issuedAt) = claims

        val invalidatedAt = try {
            tokenInvalidationRepository.findInvalidatedAt(userId)
        } catch (e: DataAccessException) {
            logger.warn("토큰 무효화 조회 실패: userId={}", userId, e)
            null
        }

        if (invalidatedAt != null && issuedAt.isBefore(invalidatedAt)) {
            throw BusinessException(AuthErrorCode.UNAUTHORIZED)
        }

        return UsernamePasswordAuthenticationToken.authenticated(
            AuthUser(userId, token),
            null,
            listOf(SimpleGrantedAuthority(AuthUser.AUTHORITY)),
        )
    }

    /**
     * 비활성화한 어드민의 토큰은 만료 전이라도 거절한다. 어드민 요청에만 조회가 한 번 더해진다.
     */
    private fun getAdminAuthentication(claims: AdminTokenClaims): Authentication {
        if (!adminRepository.existsByIdAndDisabledAtIsNull(claims.adminId)) {
            throw BusinessException(AuthErrorCode.UNAUTHORIZED)
        }

        return UsernamePasswordAuthenticationToken.authenticated(
            AuthAdmin(claims.adminId),
            null,
            listOf(SimpleGrantedAuthority(AuthAdmin.AUTHORITY)),
        )
    }

    fun createTokenPair(userId: Long): Pair<String, String> {
        val access = jwtProvider.createAccessToken(userId)
        val refresh = jwtProvider.createRefreshToken(userId)
        return Pair(access, refresh)
    }

    fun createEmailChallengeToken(email: String, purpose: AuthPurpose): String =
        jwtProvider.createEmailChallengeToken(email, purpose)

    fun getEmailChallengeClaims(token: String): EmailChallengeClaims = try {
        jwtProvider.getEmailChallengeClaims(token)
    } catch (e: ExpiredJwtException) {
        throw BusinessException(AuthErrorCode.EMAIL_CODE_EXPIRED)
    } catch (e: JwtException) {
        throw BusinessException(AuthErrorCode.INVALID_EMAIL_CHALLENGE_TOKEN)
    }

    fun createVerifiedToken(claims: VerifiedClaims): String = jwtProvider.createVerifiedToken(claims)

    fun getVerifiedClaims(token: String): VerifiedClaims = try {
        jwtProvider.getVerifiedClaims(token)
    } catch (e: ExpiredJwtException) {
        throw BusinessException(AuthErrorCode.VERIFICATION_EXPIRED)
    } catch (e: JwtException) {
        throw BusinessException(AuthErrorCode.INVALID_VERIFIED_TOKEN)
    }

    fun getUserId(token: String, type: TokenType): Long = jwtProvider.getUserId(token, type)
}

/**
 * 요청 인증에 쓰는 토큰의 클레임. 서명을 검증한 뒤 type 클레임으로 갈라 반환한다.
 */
sealed interface AuthenticationClaims

data class AccessTokenClaims(val userId: Long, val issuedAt: Instant) : AuthenticationClaims

data class AdminTokenClaims(val adminId: Long) : AuthenticationClaims

data class EmailChallengeClaims(val email: String, val purpose: AuthPurpose)

data class VerifiedClaims(
    val provider: AuthProvider,
    val providerUserId: String? = null,
    val email: String? = null,
    val purpose: AuthPurpose,
)
