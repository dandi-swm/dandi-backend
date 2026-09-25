package com.dandi.nyummy.auth.service

import com.dandi.nyummy.auth.config.AuthProperties
import com.dandi.nyummy.auth.dto.ConfirmAuthCodeRequest
import com.dandi.nyummy.auth.dto.PasswordResetRequest
import com.dandi.nyummy.auth.dto.SendAuthCodeRequest
import com.dandi.nyummy.auth.dto.SignUpRequest
import com.dandi.nyummy.auth.entity.RefreshToken
import com.dandi.nyummy.auth.enum.AuthPurpose
import com.dandi.nyummy.auth.repository.RefreshTokenRepository
import com.dandi.nyummy.auth.repository.TokenInvalidationRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.SesErrorCode
import com.dandi.nyummy.infra.aws.ses.SesService
import com.dandi.nyummy.profile.entity.Profile
import com.dandi.nyummy.profile.enum.Gender
import com.dandi.nyummy.profile.repository.ProfileRepository
import com.dandi.nyummy.security.jwt.JwtProperties
import com.dandi.nyummy.security.jwt.TokenService
import com.dandi.nyummy.security.jwt.TokenType
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import com.dandi.nyummy.user.service.PasswordService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthServiceTest {

    private val now = Instant.parse("2026-09-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val email = "user@nyummy.com"

    private val jwtProperties = JwtProperties(
        secretKey = "unused",
        encryptionKey = "unused",
        accessTimeToLive = Duration.ofMinutes(30),
        refreshTimeToLive = Duration.ofDays(15),
        refreshAbsoluteTimeToLive = Duration.ofDays(90),
        emailChallengeTimeToLive = Duration.ofMinutes(5),
        emailVerifiedTimeToLive = Duration.ofMinutes(30),
    )
    private val authProperties = AuthProperties(loginRedirectUrl = "dandi://home")

    private val userRepository = mockk<UserRepository>()
    private val profileRepository = mockk<ProfileRepository>()
    private val refreshTokenRepository = mockk<RefreshTokenRepository>()
    private val tokenService = mockk<TokenService>()
    private val codeService = mockk<CodeService>()
    private val sesService = mockk<SesService>(relaxUnitFun = true)
    private val passwordService = mockk<PasswordService>()
    private val tokenInvalidationRepository = mockk<TokenInvalidationRepository>(relaxUnitFun = true)

    private val authService = AuthService(
        userRepository = userRepository,
        profileRepository = profileRepository,
        refreshTokenRepository = refreshTokenRepository,
        tokenService = tokenService,
        codeService = codeService,
        sesService = sesService,
        passwordService = passwordService,
        authProperties = authProperties,
        jwtProperties = jwtProperties,
        clock = clock,
        tokenInvalidationRepository = tokenInvalidationRepository,
    )

    private fun createSignUpRequest() = SignUpRequest(
        emailVerifiedToken = "verified-token",
        password = "password1",
        confirmPassword = "password1",
        nickname = "냠미",
        gender = Gender.FEMALE,
        birth = LocalDate.of(2000, 1, 1),
        height = 165,
        weight = 55,
    )

    private fun stubVerifiedToken(purpose: AuthPurpose) {
        every {
            tokenService.getEmailAndPurpose("verified-token", TokenType.EMAIL_VERIFIED)
        } returns (email to purpose)
    }

    private fun stubChallengeToken(purpose: AuthPurpose) {
        every {
            tokenService.getEmailAndPurpose("challenge-token", TokenType.EMAIL_CHALLENGE)
        } returns (email to purpose)
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    // validateEmailForPurpose

    @Test
    fun `회원가입 용도인데 이미 가입된 이메일이면 EMAIL_ALREADY_EXISTS다`() {
        every { userRepository.existsByEmail(email) } returns true

        assertBusinessException(AuthErrorCode.EMAIL_ALREADY_EXISTS) {
            authService.validateEmailForPurpose(AuthPurpose.SIGNUP, email)
        }
    }

    @Test
    fun `회원가입 용도이고 미가입 이메일이면 통과한다`() {
        every { userRepository.existsByEmail(email) } returns false

        authService.validateEmailForPurpose(AuthPurpose.SIGNUP, email)
    }

    @Test
    fun `비밀번호 찾기 용도인데 가입되지 않은 이메일이면 EMAIL_NOT_FOUND다`() {
        every { userRepository.existsByEmail(email) } returns false

        assertBusinessException(AuthErrorCode.EMAIL_NOT_FOUND) {
            authService.validateEmailForPurpose(AuthPurpose.RESET_PASSWORD, email)
        }
    }

    @Test
    fun `비밀번호 찾기 용도이고 가입된 이메일이면 통과한다`() {
        every { userRepository.existsByEmail(email) } returns true

        authService.validateEmailForPurpose(AuthPurpose.RESET_PASSWORD, email)
    }

    // signup

    @Test
    fun `비밀번호 찾기 용도의 토큰으로는 가입할 수 없다`() {
        stubVerifiedToken(AuthPurpose.RESET_PASSWORD)

        assertBusinessException(AuthErrorCode.UNAUTHORIZED) {
            authService.signup(createSignUpRequest())
        }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `이미 가입된 이메일이면 EMAIL_ALREADY_EXISTS다`() {
        stubVerifiedToken(AuthPurpose.SIGNUP)
        every { userRepository.existsByEmail(email) } returns true

        assertBusinessException(AuthErrorCode.EMAIL_ALREADY_EXISTS) {
            authService.signup(createSignUpRequest())
        }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `동시 가입으로 유니크 제약에 걸리면 EMAIL_ALREADY_EXISTS로 매핑한다`() {
        stubVerifiedToken(AuthPurpose.SIGNUP)
        every { userRepository.existsByEmail(email) } returns false
        every { passwordService.encodePassword("password1") } returns "encoded:password1"
        every { userRepository.save(any()) } throws DataIntegrityViolationException("duplicate email")

        assertBusinessException(AuthErrorCode.EMAIL_ALREADY_EXISTS) {
            authService.signup(createSignUpRequest())
        }
        verify(exactly = 0) { profileRepository.save(any()) }
    }

    @Test
    fun `가입에 성공하면 사용자·프로필·리프레시 토큰을 저장하고 토큰 쌍을 반환한다`() {
        stubVerifiedToken(AuthPurpose.SIGNUP)
        every { userRepository.existsByEmail(email) } returns false
        every { passwordService.encodePassword("password1") } returns "encoded:password1"
        val savedUser = slot<User>()
        every { userRepository.save(capture(savedUser)) } answers { firstArg() }
        val savedProfile = slot<Profile>()
        every { profileRepository.save(capture(savedProfile)) } answers { firstArg() }
        every { tokenService.createTokenPair(any()) } returns ("access-token" to "refresh-token")
        val savedRefreshToken = slot<RefreshToken>()
        every { refreshTokenRepository.save(capture(savedRefreshToken)) } answers { firstArg() }

        val response = authService.signup(createSignUpRequest())

        assertEquals("access-token", response.accessToken)
        assertEquals("refresh-token", response.refreshToken)

        assertEquals(email, savedUser.captured.email)
        assertEquals("encoded:password1", savedUser.captured.password)

        assertEquals(savedUser.captured.id, savedProfile.captured.userId)
        assertEquals("냠미", savedProfile.captured.nickname)
        assertEquals(LocalDate.of(2000, 1, 1), savedProfile.captured.birth)
        assertEquals(Gender.FEMALE, savedProfile.captured.gender)
        assertEquals(165, savedProfile.captured.height)
        assertEquals(55, savedProfile.captured.weight)

        assertEquals(savedUser.captured.id, savedRefreshToken.captured.userId)
        assertEquals("refresh-token", savedRefreshToken.captured.refreshToken)
        assertEquals(now.plus(Duration.ofDays(90)), savedRefreshToken.captured.absoluteExpiresAt)
    }

    // sendAuthCode

    @Test
    fun `코드 발급, 이메일 발송, 챌린지 토큰 발급 순으로 진행한다`() {
        every { userRepository.existsByEmail(email) } returns false
        every { codeService.createCodeByEmail(email, AuthPurpose.SIGNUP) } returns "123456"
        every { tokenService.createEmailChallengeToken(email, AuthPurpose.SIGNUP) } returns "challenge-token"

        val response = authService.sendAuthCode(SendAuthCodeRequest(email = email, purpose = AuthPurpose.SIGNUP))

        assertEquals("challenge-token", response.emailChallengeToken)
        verifyOrder {
            userRepository.existsByEmail(email)
            codeService.createCodeByEmail(email, AuthPurpose.SIGNUP)
            sesService.sendAuthCode(email, "123456")
            tokenService.createEmailChallengeToken(email, AuthPurpose.SIGNUP)
        }
    }

    @Test
    fun `용도 검증에 실패하면 코드를 발급하거나 발송하지 않는다`() {
        every { userRepository.existsByEmail(email) } returns true

        assertBusinessException(AuthErrorCode.EMAIL_ALREADY_EXISTS) {
            authService.sendAuthCode(SendAuthCodeRequest(email = email, purpose = AuthPurpose.SIGNUP))
        }
        verify(exactly = 0) { codeService.createCodeByEmail(any(), any()) }
        verify(exactly = 0) { sesService.sendAuthCode(any(), any()) }
    }

    @Test
    fun `이메일 발송에 실패하면 예외를 전파하고 챌린지 토큰을 발급하지 않는다`() {
        every { userRepository.existsByEmail(email) } returns false
        every { codeService.createCodeByEmail(email, AuthPurpose.SIGNUP) } returns "123456"
        every { sesService.sendAuthCode(email, "123456") } throws BusinessException(SesErrorCode.EMAIL_SEND_FAILED)

        assertBusinessException(SesErrorCode.EMAIL_SEND_FAILED) {
            authService.sendAuthCode(SendAuthCodeRequest(email = email, purpose = AuthPurpose.SIGNUP))
        }
        verify(exactly = 0) { tokenService.createEmailChallengeToken(any(), any()) }
    }

    // confirmAuthCode

    @Test
    fun `코드가 맞으면 챌린지 토큰의 용도를 승계한 인증 완료 토큰을 발급한다`() {
        stubChallengeToken(AuthPurpose.RESET_PASSWORD)
        every { codeService.confirmAuthCodeByEmail("123456", email, AuthPurpose.RESET_PASSWORD) } just Runs
        every { tokenService.createEmailVerifiedToken(email, AuthPurpose.RESET_PASSWORD) } returns "verified-token"

        val response = authService.confirmAuthCode(
            ConfirmAuthCodeRequest(authCode = "123456", emailChallengeToken = "challenge-token"),
        )

        assertEquals("verified-token", response.emailVerifiedToken)
    }

    @Test
    fun `코드 검증에 실패하면 인증 완료 토큰을 발급하지 않는다`() {
        stubChallengeToken(AuthPurpose.SIGNUP)
        every {
            codeService.confirmAuthCodeByEmail("000000", email, AuthPurpose.SIGNUP)
        } throws BusinessException(AuthErrorCode.EMAIL_CODE_MISMATCH)

        assertBusinessException(AuthErrorCode.EMAIL_CODE_MISMATCH) {
            authService.confirmAuthCode(
                ConfirmAuthCodeRequest(authCode = "000000", emailChallengeToken = "challenge-token"),
            )
        }
        verify(exactly = 0) { tokenService.createEmailVerifiedToken(any(), any()) }
    }

    // resetPassword

    @Test
    fun `회원가입 용도의 토큰으로는 비밀번호를 재설정할 수 없다`() {
        stubVerifiedToken(AuthPurpose.SIGNUP)

        assertBusinessException(AuthErrorCode.UNAUTHORIZED) {
            authService.resetPassword(PasswordResetRequest(emailVerifiedToken = "verified-token"))
        }
        verify(exactly = 0) { passwordService.createTempPasswordByEmail(any()) }
    }

    @Test
    fun `토큰의 이메일로 가입된 사용자가 없으면 EMAIL_NOT_FOUND다`() {
        stubVerifiedToken(AuthPurpose.RESET_PASSWORD)
        every { userRepository.findByEmail(email) } returns null

        assertBusinessException(AuthErrorCode.EMAIL_NOT_FOUND) {
            authService.resetPassword(PasswordResetRequest(emailVerifiedToken = "verified-token"))
        }
        verify(exactly = 0) { passwordService.createTempPasswordByEmail(any()) }
    }

    @Test
    fun `비밀번호 교체, 토큰 무효화, 임시 비밀번호 발송 순으로 진행한다`() {
        stubVerifiedToken(AuthPurpose.RESET_PASSWORD)
        val user = User(email = email, password = "encoded:old")
        every { userRepository.findByEmail(email) } returns user
        every { passwordService.createTempPasswordByEmail(email) } returns "Temp12345678"

        authService.resetPassword(PasswordResetRequest(emailVerifiedToken = "verified-token"))

        verifyOrder {
            passwordService.createTempPasswordByEmail(email)
            tokenInvalidationRepository.createInvalidatedAt(user.id, now)
            sesService.sendTempPassword(email, "Temp12345678")
        }
    }

    @Test
    fun `토큰 무효화 기록이 실패해도 임시 비밀번호는 발송한다`() {
        stubVerifiedToken(AuthPurpose.RESET_PASSWORD)
        val user = User(email = email, password = "encoded:old")
        every { userRepository.findByEmail(email) } returns user
        every { passwordService.createTempPasswordByEmail(email) } returns "Temp12345678"
        every {
            tokenInvalidationRepository.createInvalidatedAt(user.id, now)
        } throws DataAccessResourceFailureException("redis down")

        authService.resetPassword(PasswordResetRequest(emailVerifiedToken = "verified-token"))

        verify(exactly = 1) { sesService.sendTempPassword(email, "Temp12345678") }
    }
}
