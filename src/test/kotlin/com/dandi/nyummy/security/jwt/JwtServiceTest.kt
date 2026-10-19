package com.dandi.nyummy.security.jwt

import com.dandi.nyummy.admin.repository.AdminRepository
import com.dandi.nyummy.auth.repository.TokenInvalidationRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.security.AuthAdmin
import com.dandi.nyummy.security.AuthUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.QueryTimeoutException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 요청 토큰을 type별로 나눠 principal과 권한을 정하는 흐름을 다룬다. 서명·type 파싱은 JwtProvider가 맡는다.
 */
class JwtServiceTest {

    private val token = "request-token"
    private val userId = 1L
    private val adminId = 7L
    private val issuedAt = Instant.parse("2026-10-19T03:00:00Z")

    private val jwtProvider = mockk<JwtProvider>()
    private val tokenInvalidationRepository = mockk<TokenInvalidationRepository>()
    private val adminRepository = mockk<AdminRepository>()

    private val jwtService = JwtService(
        jwtProvider = jwtProvider,
        tokenInvalidationRepository = tokenInvalidationRepository,
        adminRepository = adminRepository,
    )

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    private fun givenUserToken() {
        every { jwtProvider.getAuthenticationClaims(token) } returns AccessTokenClaims(userId, issuedAt)
    }

    private fun givenAdminToken() {
        every { jwtProvider.getAuthenticationClaims(token) } returns AdminTokenClaims(adminId)
    }

    // AccessToken

    @Test
    fun `AccessToken이면 AuthUser와 ROLE_USER 권한 하나만 부여한다`() {
        givenUserToken()
        every { tokenInvalidationRepository.findInvalidatedAt(userId) } returns null

        val authentication = jwtService.getAuthentication(token)

        assertEquals(AuthUser(userId, token), authentication.principal)
        assertEquals(listOf(AuthUser.AUTHORITY), authentication.authorities.map { it.authority })
        verify(exactly = 0) { adminRepository.existsByIdAndDisabledAtIsNull(any()) }
    }

    @Test
    fun `무효화 시각보다 먼저 발급된 AccessToken은 UNAUTHORIZED로 거절한다`() {
        givenUserToken()
        every { tokenInvalidationRepository.findInvalidatedAt(userId) } returns issuedAt.plusSeconds(1)

        assertBusinessException(AuthErrorCode.UNAUTHORIZED) {
            jwtService.getAuthentication(token)
        }
    }

    @Test
    fun `무효화 시각 조회가 실패해도 AccessToken 인증은 통과시킨다`() {
        givenUserToken()
        every { tokenInvalidationRepository.findInvalidatedAt(userId) } throws QueryTimeoutException("Redis 응답 없음")

        val authentication = jwtService.getAuthentication(token)

        assertEquals(AuthUser(userId, token), authentication.principal)
    }

    // 어드민 토큰

    @Test
    fun `활성 어드민의 토큰이면 AuthAdmin과 ROLE_ADMIN 권한 하나만 부여한다`() {
        givenAdminToken()
        every { adminRepository.existsByIdAndDisabledAtIsNull(adminId) } returns true

        val authentication = jwtService.getAuthentication(token)

        assertEquals(AuthAdmin(adminId), authentication.principal)
        assertEquals(listOf(AuthAdmin.AUTHORITY), authentication.authorities.map { it.authority })
    }

    @Test
    fun `비활성화됐거나 없는 어드민의 토큰은 만료 전이라도 UNAUTHORIZED로 거절한다`() {
        givenAdminToken()
        every { adminRepository.existsByIdAndDisabledAtIsNull(adminId) } returns false

        assertBusinessException(AuthErrorCode.UNAUTHORIZED) {
            jwtService.getAuthentication(token)
        }
    }

    @Test
    fun `어드민 토큰은 사용자 무효화 시각을 조회하지 않는다`() {
        givenAdminToken()
        every { adminRepository.existsByIdAndDisabledAtIsNull(adminId) } returns true

        jwtService.getAuthentication(token)

        verify(exactly = 0) { tokenInvalidationRepository.findInvalidatedAt(any()) }
    }
}
