package com.dandi.nyummy.security.jwt

import com.dandi.nyummy.auth.enum.AuthPurpose
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtParser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Component
class JwtProvider(private val jwtProperties: JwtProperties, private val clock: Clock) {

    private val secretKey: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.secretKey))
    private val encryptionKey: SecretKey = SecretKeySpec(Decoders.BASE64.decode(jwtProperties.encryptionKey), "AES")

    private val jwsParser: JwtParser = Jwts.parser()
        .verifyWith(secretKey)
        .clockSkewSeconds(60)
        .clock { Date.from(clock.instant()) }
        .build()

    private val jweParser: JwtParser = Jwts.parser()
        .decryptWith(encryptionKey)
        .clockSkewSeconds(60)
        .clock { Date.from(clock.instant()) }
        .build()

    private fun getClaims(token: String, type: TokenType): Claims {
        val claims = if (type.isEncrypted) {
            jweParser.parseEncryptedClaims(token).payload
        } else {
            jwsParser.parseSignedClaims(token).payload
        }

        if (claims["type"] != type.value) {
            throw BusinessException(AuthErrorCode.UNAUTHORIZED)
        }

        return claims
    }

    /**
     * AccessToken에서 사용자 ID와 발급 시각(iat)을 함께 꺼낸다.
     *
     * 둘을 따로 조회하면 매 요청 토큰을 두 번 파싱해 서명 검증도 두 번 하게 되므로, 한 번에 읽는다.
     */
    fun getAccessTokenClaims(token: String): AccessTokenClaims {
        val claims = getClaims(token, TokenType.ACCESS)
        val userId = claims.subject?.toLongOrNull() ?: throw BusinessException(AuthErrorCode.UNAUTHORIZED)
        return AccessTokenClaims(userId, claims.issuedAt.toInstant())
    }

    private fun createToken(userId: Long, type: TokenType): String {
        val now = clock.instant()

        val timeToLive = getTimeToLive(type)

        return Jwts.builder()
            .subject(userId.toString())
            .claim("type", type.value)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(timeToLive)))
            .signWith(secretKey)
            .compact()
    }

    private fun createToken(email: String, type: TokenType, purpose: AuthPurpose? = null): String {
        val now = clock.instant()

        val timeToLive = getTimeToLive(type)

        val builder = Jwts.builder()
            .subject(email)
            .claim("type", type.value)

        if (purpose != null) {
            builder.claim("purpose", purpose.name)
        }

        return builder
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(timeToLive)))
            .encryptWith(encryptionKey, Jwts.ENC.A256GCM)
            .compact()
    }

    private fun getTimeToLive(type: TokenType): Duration = when (type) {
        TokenType.ACCESS -> jwtProperties.accessTimeToLive
        TokenType.REFRESH -> jwtProperties.refreshTimeToLive
        TokenType.EMAIL_CHALLENGE -> jwtProperties.emailChallengeTimeToLive
        TokenType.EMAIL_VERIFIED -> jwtProperties.emailVerifiedTimeToLive
    }

    fun createEmailChallengeToken(email: String, purpose: AuthPurpose): String =
        createToken(email, TokenType.EMAIL_CHALLENGE, purpose)

    fun createEmailVerifiedToken(email: String, purpose: AuthPurpose): String =
        createToken(email, TokenType.EMAIL_VERIFIED, purpose)

    fun createAccessToken(userId: Long): String = createToken(userId, TokenType.ACCESS)

    fun createRefreshToken(userId: Long): String = createToken(userId, TokenType.REFRESH)

    fun getUserId(token: String, type: TokenType): Long = getClaims(token, type).subject?.toLongOrNull()
        ?: throw BusinessException(AuthErrorCode.UNAUTHORIZED)

    fun getEmail(token: String, type: TokenType): String = getClaims(token, type).subject
        ?: throw BusinessException(AuthErrorCode.UNAUTHORIZED)

    fun getPurpose(token: String, type: TokenType): AuthPurpose {
        val purpose = getClaims(token, type)["purpose"] as? String
            ?: throw BusinessException(AuthErrorCode.UNAUTHORIZED)

        return AuthPurpose.entries.find { it.name == purpose }
            ?: throw BusinessException(AuthErrorCode.UNAUTHORIZED)
    }

    fun getExpiration(token: String, type: TokenType): Date = getClaims(token, type).expiration
}
