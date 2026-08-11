package com.dandi.nyummy.security.jwt

import io.jsonwebtoken.ExpiredJwtException
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

@Component
class JwtProvider(private val jwtProperties: JwtProperties, private val clock: Clock) {

    private val secretKey: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.secretKey))
    private val parser: JwtParser = Jwts.parser()
        .verifyWith(secretKey)
        .clockSkewSeconds(60)
        .clock { Date.from(clock.instant()) }
        .build()

    fun createAccessToken(userId: Long): String = createToken(userId, "access", jwtProperties.accessTimeToLive)

    fun createRefreshToken(userId: Long): String = createToken(userId, "refresh", jwtProperties.refreshTimeToLive)

    private fun createToken(userId: Long, type: String, timeToLive: Duration): String {
        val now = clock.instant()

        return Jwts.builder()
            .subject(userId.toString())
            .claim("type", type)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(timeToLive)))
            .signWith(secretKey)
            .compact()
    }

    fun getUserId(token: String, expectedType: TokenType): Long {
        val claims = try {
            parser.parseSignedClaims(token).payload
        } catch (e: ExpiredJwtException) {
            throw JwtException("만료된 JWT 토큰입니다.", e)
        } catch (e: JwtException) {
            throw JwtException("유효하지 않은 토큰입니다.", e)
        }

        if (claims["type"] != expectedType.value) {
            throw JwtException("${expectedType.value} 토큰이 아닙니다.")
        }

        return claims.subject?.toLongOrNull() ?: throw JwtException("유효한 sub가 아닙니다.")
    }
}
