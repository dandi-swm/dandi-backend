package com.dandi.nyummy.security.jwt

import com.dandi.nyummy.security.AuthUser
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import java.util.*

@Service
class TokenService(private val jwtProvider: JwtProvider) {

    fun getAuthentication(token: String): Authentication {
        val userId = jwtProvider.getUserId(token, TokenType.ACCESS)

        return UsernamePasswordAuthenticationToken.authenticated(AuthUser(userId, token), null, emptyList())
    }

    fun createTokenPair(userId: Long): Pair<String, String> {
        val access = jwtProvider.createAccessToken(userId)
        val refresh = jwtProvider.createRefreshToken(userId)
        return Pair(access, refresh)
    }

    fun getUserId(token: String, type: TokenType): Long = jwtProvider.getUserId(token, type)

    fun getExpiration(token: String, type: TokenType): Date = jwtProvider.getExpiration(token, type)
}
