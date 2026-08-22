package com.dandi.nyummy.security.jwt

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtParser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.*
import javax.crypto.SecretKey

@Component
class JwtProvider(private val jwtProperties: JwtProperties, private val clock: Clock) {

    private val secretKey: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.secretKey))
    private val parser: JwtParser = Jwts.parser()
        .verifyWith(secretKey)
        .clockSkewSeconds(60)
        .clock { Date.from(clock.instant()) }
        .build()

    private fun getClaims(token: String, type: TokenType): Claims {
        val claims = parser.parseSignedClaims(token).payload

        if (claims["type"] != type.value) {
            throw BusinessException(AuthErrorCode.UNAUTHORIZED)
        }

        return claims
    }

    private fun createToken(userId: Long, type: TokenType): String {
        val now = clock.instant()

        val timeToLive = when (type) {
            TokenType.ACCESS -> jwtProperties.accessTimeToLive
            TokenType.REFRESH -> jwtProperties.refreshTimeToLive
        }

        return Jwts.builder()
            .subject(userId.toString())
            .claim("type", type.value)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(timeToLive)))
            .signWith(secretKey)
            .compact()
    }

    fun createAccessToken(userId: Long): String = createToken(userId, TokenType.ACCESS)

    fun createRefreshToken(userId: Long): String = createToken(userId, TokenType.REFRESH)

    fun getUserId(token: String, type: TokenType): Long = getClaims(token, type).subject?.toLongOrNull()
        ?: throw BusinessException(AuthErrorCode.UNAUTHORIZED)

    fun getExpiration(token: String, type: TokenType): Date = getClaims(token, type).expiration
}
