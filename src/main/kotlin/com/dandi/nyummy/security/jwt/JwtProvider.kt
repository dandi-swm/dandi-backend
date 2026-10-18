package com.dandi.nyummy.security.jwt

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.auth.enum.AuthPurpose
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.JwtParser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.*
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.enums.enumEntries

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

        // 여러 토큰 타입이 공유하는 경로라 에러 코드를 정하지 않고 JwtException으로 올린다 — 호출부가 토큰별 코드로 변환
        if (claims["type"] != type.value) {
            throw JwtException("토큰 타입이 일치하지 않습니다: expected=${type.value}")
        }

        return claims
    }

    /**
     * 요청 인증용 토큰(AccessToken, 어드민 토큰)을 한 번 파싱해 type에 맞는 클레임으로 꺼낸다.
     *
     * 필터는 요청이 어느 토큰으로 왔는지 미리 알 수 없다. 타입마다 따로 파싱하면 서명 검증을 여러 번 하게 되므로,
     * 한 번 검증한 뒤 type으로 가른다. 어느 경로에 어떤 토큰을 받을지는 SecurityConfig가 권한으로 정한다.
     */
    fun getAuthenticationClaims(token: String): AuthenticationClaims {
        val claims = jwsParser.parseSignedClaims(token).payload
        val subjectId = claims.subject?.toLongOrNull() ?: throw BusinessException(AuthErrorCode.UNAUTHORIZED)

        return when (claims["type"]) {
            TokenType.ACCESS.value -> AccessTokenClaims(subjectId, claims.issuedAt.toInstant())
            TokenType.ADMIN.value -> AdminTokenClaims(subjectId)
            else -> throw JwtException("요청 인증에 쓸 수 없는 토큰 타입입니다: ${claims["type"]}")
        }
    }

    private fun createToken(subjectId: Long, type: TokenType): String {
        val now = clock.instant()

        val timeToLive = getTimeToLive(type)

        return Jwts.builder()
            .subject(subjectId.toString())
            .claim("type", type.value)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(timeToLive)))
            .signWith(secretKey)
            .compact()
    }

    private fun createEncryptedToken(subject: String?, type: TokenType, claims: Map<String, String>): String {
        val now = clock.instant()

        val timeToLive = getTimeToLive(type)

        return Jwts.builder()
            .subject(subject)
            .claim("type", type.value)
            .claims(claims)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(timeToLive)))
            .encryptWith(encryptionKey, Jwts.ENC.A256GCM)
            .compact()
    }

    private fun getTimeToLive(type: TokenType): Duration = when (type) {
        TokenType.ACCESS -> jwtProperties.accessTimeToLive
        TokenType.REFRESH -> jwtProperties.refreshTimeToLive
        TokenType.EMAIL_CHALLENGE -> jwtProperties.emailChallengeTimeToLive
        TokenType.VERIFIED -> jwtProperties.verifiedTimeToLive
        TokenType.ADMIN -> jwtProperties.adminTimeToLive
    }

    fun createEmailChallengeToken(email: String, purpose: AuthPurpose): String =
        createEncryptedToken(email, TokenType.EMAIL_CHALLENGE, mapOf("purpose" to purpose.name))

    fun getEmailChallengeClaims(token: String): EmailChallengeClaims {
        val claims = getClaims(token, TokenType.EMAIL_CHALLENGE)

        val email = claims.subject
            ?: throw BusinessException(AuthErrorCode.INVALID_EMAIL_CHALLENGE_TOKEN)

        val purpose = claims.getEnum<AuthPurpose>("purpose")
            ?: throw BusinessException(AuthErrorCode.INVALID_EMAIL_CHALLENGE_TOKEN)

        return EmailChallengeClaims(email, purpose)
    }

    fun createVerifiedToken(claims: VerifiedClaims): String = createEncryptedToken(
        subject = null,
        type = TokenType.VERIFIED,
        claims = buildMap {
            put("provider", claims.provider.name)
            put("purpose", claims.purpose.name)
            claims.providerUserId?.let { put("providerUserId", it) }
            claims.email?.let { put("email", it) }
        },
    )

    fun getVerifiedClaims(token: String): VerifiedClaims {
        val claims = getClaims(token, TokenType.VERIFIED)

        val provider = claims.getEnum<AuthProvider>("provider")
            ?: throw BusinessException(AuthErrorCode.INVALID_VERIFIED_TOKEN)

        val purpose = claims.getEnum<AuthPurpose>("purpose")
            ?: throw BusinessException(AuthErrorCode.INVALID_VERIFIED_TOKEN)

        return VerifiedClaims(
            provider = provider,
            providerUserId = claims["providerUserId"] as? String,
            email = claims["email"] as? String,
            purpose = purpose,
        )
    }

    fun createAccessToken(userId: Long): String = createToken(userId, TokenType.ACCESS)

    fun createRefreshToken(userId: Long): String = createToken(userId, TokenType.REFRESH)

    fun getUserId(token: String, type: TokenType): Long = getClaims(token, type).subject?.toLongOrNull()
        ?: throw BusinessException(AuthErrorCode.UNAUTHORIZED)

    private inline fun <reified E : Enum<E>> Claims.getEnum(name: String): E? {
        val value = this[name] as? String ?: return null

        return enumEntries<E>().find { it.name == value }
    }
}
