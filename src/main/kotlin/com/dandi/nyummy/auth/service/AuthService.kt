package com.dandi.nyummy.auth.service

import com.dandi.nyummy.auth.config.AuthProperties
import com.dandi.nyummy.auth.dto.LoginRequest
import com.dandi.nyummy.auth.dto.LoginResponse
import com.dandi.nyummy.auth.dto.RefreshRequest
import com.dandi.nyummy.auth.dto.RefreshResponse
import com.dandi.nyummy.auth.entity.RefreshToken
import com.dandi.nyummy.auth.repository.RefreshTokenRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.security.jwt.TokenService
import com.dandi.nyummy.security.jwt.TokenType
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService,
    private val authProperties: AuthProperties,
) {

    /**
     * 이메일과 비밀번호로 사용자를 인증하고 AccessToken·RefreshToken을 발급한다.
     * 기존에 발급된 RefreshToken이 있으면 새 토큰으로 교체(rotate)하고, 없으면 새로 저장한다.
     *
     * @param request 로그인 요청 정보를 담은 [LoginRequest] (이메일, 비밀번호)
     * @return 리다이렉트 URL과 AccessToken·RefreshToken을 담은 [LoginResponse]
     * @throws BusinessException [AuthErrorCode.INVALID_CREDENTIALS] 이메일에 해당하는 사용자가 없거나 비밀번호가 일치하지 않는 경우
     */
    @Transactional
    fun login(request: LoginRequest): LoginResponse {
        val user = userRepository.findByEmail(request.email)
            ?: throw BusinessException(AuthErrorCode.INVALID_CREDENTIALS)

        if (!passwordEncoder.matches(request.password, user.password)) {
            throw BusinessException(AuthErrorCode.INVALID_CREDENTIALS)
        }

        val userId = user.id

        val (newAccessToken, newRefreshToken) = tokenService.createTokenPair(userId)
        val newExpiresAt = tokenService.getExpiration(newRefreshToken, TokenType.REFRESH).toInstant()

        val existingToken = refreshTokenRepository.findByUserId(userId)

        if (existingToken != null) {
            existingToken.rotate(newRefreshToken, newExpiresAt)
        } else {
            refreshTokenRepository.save(
                RefreshToken(
                    refreshToken = newRefreshToken,
                    userId = userId,
                    expiresAt = newExpiresAt,
                ),
            )
        }

        val redirectUrl = authProperties.loginRedirectUrl

        return LoginResponse(redirectUrl, newAccessToken, newRefreshToken)
    }

    /**
     * 리프레시 토큰을 검증하고 새 AccessToken·RefreshToken을 발급한다(rotate).
     *
     * @param request 리프레시 요청 정보를 담은 [RefreshRequest] (리프레시 토큰)
     * @return 새로 발급된 AccessToken·RefreshToken을 담은 [RefreshResponse]
     * @throws BusinessException [AuthErrorCode.INVALID_REFRESH_TOKEN] 토큰이 유효하지 않거나(서명·만료·타입 불일치),
     * 저장된 리프레시 토큰이 없거나, 이미 교체(rotate)된 토큰인 경우
     */
    @Transactional
    fun refresh(request: RefreshRequest): RefreshResponse {
        val userId = try {
            tokenService.getUserId(request.refreshToken, TokenType.REFRESH)
        } catch (e: Exception) {
            throw BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN)
        }

        val existingToken = refreshTokenRepository.findByUserId(userId)
            ?: throw BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN)

        if (existingToken.refreshToken != request.refreshToken) {
            throw BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN)
        }

        val (newAccessToken, newRefreshToken) = tokenService.createTokenPair(userId)
        val newExpiresAt = tokenService.getExpiration(newRefreshToken, TokenType.REFRESH).toInstant()

        existingToken.rotate(newRefreshToken, newExpiresAt)

        return RefreshResponse(newAccessToken, newRefreshToken)
    }

    /**
     * 사용자의 RefreshToken을 삭제해 로그아웃 처리한다.
     *
     * 저장된 RefreshToken이 없어도 이미 로그아웃된 상태로 보고 정상 처리한다(멱등).
     *
     * @param userId 로그아웃할 사용자 ID
     * @param accessToken 블랙리스트 등록에 사용할 AccessToken (Redis 도입 전까지 미사용)
     */
    @Transactional
    fun logout(userId: Long, accessToken: String) {
        // TODO: accessToken 레디스 블랙리스트에 저장

        val refreshToken = refreshTokenRepository.findByUserId(userId)
            ?: return

        refreshTokenRepository.delete(refreshToken)
    }
}
