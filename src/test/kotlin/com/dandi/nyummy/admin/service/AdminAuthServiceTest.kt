package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.dto.AdminLoginRequest
import com.dandi.nyummy.admin.dto.AdminResponse
import com.dandi.nyummy.admin.entity.Admin
import com.dandi.nyummy.admin.repository.AdminRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.infra.oauth.google.AdminGoogleOAuthClient
import com.dandi.nyummy.security.jwt.JwtProperties
import com.dandi.nyummy.security.jwt.JwtService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Google 이메일로 어드민을 찾아 토큰을 발급하는 흐름을 다룬다. ID 토큰 서명·aud 검증은 디코더가 맡는다.
 */
class AdminAuthServiceTest {

    private val adminId = 1L
    private val email = "admin@nyummy.co.kr"
    private val idToken = "google-id-token"
    private val adminToken = "admin-token"
    private val adminTimeToLive = Duration.ofHours(8)

    private val adminGoogleOAuthClient = mockk<AdminGoogleOAuthClient>()
    private val adminRepository = mockk<AdminRepository>()
    private val jwtService = mockk<JwtService>()
    private val jwtProperties = mockk<JwtProperties>()

    private val adminAuthService = AdminAuthService(
        adminGoogleOAuthClient = adminGoogleOAuthClient,
        adminRepository = adminRepository,
        jwtService = jwtService,
        jwtProperties = jwtProperties,
    )

    init {
        every { adminGoogleOAuthClient.getEmail(idToken) } returns email
        every { jwtService.createAdminToken(adminId) } returns adminToken
        every { jwtProperties.adminTimeToLive } returns adminTimeToLive
    }

    private fun admin(disabledAt: Instant? = null) = Admin(email = email, name = "운영자").apply {
        this.disabledAt = disabledAt
        // id는 DB가 채우는 val이라 테스트에서만 직접 넣는다.
        ReflectionTestUtils.setField(this, "id", adminId)
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    @Test
    fun `등록된 활성 어드민이면 어드민 토큰과 토큰 수명, 어드민 정보를 반환한다`() {
        every { adminRepository.findByEmail(email) } returns admin()

        val result = adminAuthService.login(AdminLoginRequest(idToken))

        assertEquals(adminToken, result.adminToken)
        assertEquals(adminTimeToLive, result.timeToLive)
        assertEquals(AdminResponse(adminId = adminId, email = email, name = "운영자"), result.admin)
    }

    @Test
    fun `Google 이메일에 대문자가 섞여 있어도 소문자로 바꿔 조회한다`() {
        every { adminGoogleOAuthClient.getEmail(idToken) } returns "Admin@Nyummy.CO.KR"
        every { adminRepository.findByEmail(email) } returns admin()

        adminAuthService.login(AdminLoginRequest(idToken))

        verify(exactly = 1) { adminRepository.findByEmail(email) }
    }

    @Test
    fun `등록되지 않은 이메일이면 NOT_ADMIN으로 거절하고 토큰을 만들지 않는다`() {
        every { adminRepository.findByEmail(email) } returns null

        assertBusinessException(AdminErrorCode.NOT_ADMIN) {
            adminAuthService.login(AdminLoginRequest(idToken))
        }
        verify(exactly = 0) { jwtService.createAdminToken(any()) }
    }

    @Test
    fun `비활성화된 어드민이면 NOT_ADMIN으로 거절하고 토큰을 만들지 않는다`() {
        every { adminRepository.findByEmail(email) } returns admin(disabledAt = Instant.parse("2026-10-19T00:00:00Z"))

        assertBusinessException(AdminErrorCode.NOT_ADMIN) {
            adminAuthService.login(AdminLoginRequest(idToken))
        }
        verify(exactly = 0) { jwtService.createAdminToken(any()) }
    }

    @Test
    fun `Google ID 토큰 검증이 실패하면 그 예외를 그대로 올리고 어드민을 조회하지 않는다`() {
        every { adminGoogleOAuthClient.getEmail(idToken) } throws BusinessException(AuthErrorCode.INVALID_OAUTH_TOKEN)

        assertBusinessException(AuthErrorCode.INVALID_OAUTH_TOKEN) {
            adminAuthService.login(AdminLoginRequest(idToken))
        }
        verify(exactly = 0) { adminRepository.findByEmail(any()) }
        verify(exactly = 0) { jwtService.createAdminToken(any()) }
    }
}
