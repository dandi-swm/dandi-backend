package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminLoginRequest
import com.dandi.nyummy.admin.dto.AdminLoginResult
import com.dandi.nyummy.admin.mapper.toAdminResponse
import com.dandi.nyummy.admin.repository.AdminRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.infra.oauth.google.AdminGoogleOAuthClient
import com.dandi.nyummy.security.jwt.JwtProperties
import com.dandi.nyummy.security.jwt.JwtService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.Locale

@Service
@ConditionalOnAdminEnabled
class AdminAuthService(
    private val adminGoogleOAuthClient: AdminGoogleOAuthClient,
    private val adminRepository: AdminRepository,
    private val jwtService: JwtService,
    private val jwtProperties: JwtProperties,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AdminAuthService::class.java)
    }

    /**
     * Google ID 토큰으로 어드민을 확인하고 어드민 토큰을 발급한다.
     *
     * 트랜잭션을 걸지 않는다. Google 공개키 조회(외부 호출)가 DB 커넥션을 잡은 채로 기다리지 않게,
     * 검증을 먼저 끝내고 조회는 리포지토리 기본 트랜잭션으로 한 번만 한다.
     */
    fun login(request: AdminLoginRequest): AdminLoginResult {
        val email = adminGoogleOAuthClient.getEmail(request.idToken).lowercase(Locale.ROOT)

        val admin = adminRepository.findByEmail(email)?.takeUnless { it.isDisabled }
            ?: run {
                logger.warn("어드민 로그인 거절: email={}", email)
                throw BusinessException(AdminErrorCode.NOT_ADMIN)
            }

        return AdminLoginResult(
            adminToken = jwtService.createAdminToken(admin.id),
            timeToLive = jwtProperties.adminTimeToLive,
            admin = admin.toAdminResponse(),
        )
    }
}
