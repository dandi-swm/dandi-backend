package com.dandi.nyummy.user.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.auth.repository.TokenInvalidationRepository
import com.dandi.nyummy.auth.service.RefreshTokenService
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.infra.oauth.apple.AppleOAuthClient
import com.dandi.nyummy.security.jwt.JwtService
import com.dandi.nyummy.user.dto.UpdateProfileRequest
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.enum.Gender
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.user.repository.UserRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.data.repository.findByIdOrNull
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val profileRepository = mockk<ProfileRepository>()
    private val tokenInvalidationRepository = mockk<TokenInvalidationRepository>(relaxed = true)
    private val userWithdrawalTransactionService = mockk<UserWithdrawalTransactionService>()
    private val imageService = mockk<ImageService>()
    private val appleOAuthClient = mockk<AppleOAuthClient>()
    private val clock = Clock.fixed(Instant.parse("2026-11-03T03:00:00Z"), ZoneId.of("Asia/Seoul"))

    private val userId = 1L

    private val userService = UserService(
        userRepository = userRepository,
        profileRepository = profileRepository,
        passwordService = mockk<PasswordService>(),
        jwtService = mockk<JwtService>(),
        refreshTokenService = mockk<RefreshTokenService>(),
        tokenInvalidationRepository = tokenInvalidationRepository,
        clock = clock,
        userWithdrawalTransactionService = userWithdrawalTransactionService,
        imageService = imageService,
        coinService = mockk<CoinService>(),
        appleOAuthClient = appleOAuthClient,
    )

    // ---------------------------------------------------------------- 프로필 변경

    @Test
    fun `보낸 항목만 바꾸고 생략한 항목은 그대로 둔다`() {
        // given
        val profile = givenProfile()

        // when
        userService.updateProfile(userId, UpdateProfileRequest(nickname = "초코집사", weight = 55))

        // then
        assertEquals("초코집사", profile.nickname)
        assertEquals(55, profile.weight)
        assertEquals(LocalDate.of(2000, 1, 1), profile.birth)
        assertEquals(Gender.FEMALE, profile.gender)
        assertEquals(160, profile.height)
    }

    @Test
    fun `다섯 항목을 모두 바꾼다`() {
        // given
        val profile = givenProfile()

        // when
        userService.updateProfile(
            userId,
            UpdateProfileRequest(
                nickname = "초코집사",
                birth = LocalDate.of(1995, 5, 5),
                gender = Gender.MALE,
                height = 175,
                weight = 70,
            ),
        )

        // then
        assertEquals("초코집사", profile.nickname)
        assertEquals(LocalDate.of(1995, 5, 5), profile.birth)
        assertEquals(Gender.MALE, profile.gender)
        assertEquals(175, profile.height)
        assertEquals(70, profile.weight)
    }

    @Test
    fun `모두 생략하면 아무것도 바꾸지 않는다`() {
        // given
        val profile = givenProfile()

        // when
        userService.updateProfile(userId, UpdateProfileRequest())

        // then
        assertEquals("나비집사", profile.nickname)
        assertEquals(LocalDate.of(2000, 1, 1), profile.birth)
        assertEquals(Gender.FEMALE, profile.gender)
        assertEquals(160, profile.height)
        assertEquals(50, profile.weight)
    }

    @Test
    fun `프로필이 없으면 PROFILE_NOT_FOUND를 던진다`() {
        // given
        every { profileRepository.findProfileByUserId(userId) } returns null

        // when & then
        assertBusinessException(UserErrorCode.PROFILE_NOT_FOUND) {
            userService.updateProfile(userId, UpdateProfileRequest(nickname = "초코집사"))
        }
    }

    // ---------------------------------------------------------------- 회원 탈퇴

    @Test
    fun `Apple 계정은 Apple 연결을 먼저 끊고 탈퇴한다`() {
        // given
        givenUser(AuthProvider.APPLE, providerUserId = "apple-sub")
        givenWithdrawal()
        justRun { appleOAuthClient.withdrawUser("apple-code", "apple-sub") }

        // when
        userService.withdrawUser(userId, "apple-code")

        // then
        verifyOrder {
            appleOAuthClient.withdrawUser("apple-code", "apple-sub")
            userWithdrawalTransactionService.withdrawUser(userId)
        }
    }

    @Test
    fun `Apple 계정이 authorizationCode 없이 탈퇴하면 거절하고 아무것도 지우지 않는다`() {
        // given
        givenUser(AuthProvider.APPLE, providerUserId = "apple-sub")

        // when, then
        assertBusinessException(AuthErrorCode.OAUTH_AUTHORIZATION_CODE_REQUIRED) {
            userService.withdrawUser(userId, null)
        }
        verify(exactly = 0) { appleOAuthClient.withdrawUser(any(), any()) }
        verify(exactly = 0) { userWithdrawalTransactionService.withdrawUser(any()) }
    }

    @Test
    fun `Apple 계정의 authorizationCode가 빈 문자열이면 없는 것으로 보고 거절한다`() {
        // given
        givenUser(AuthProvider.APPLE, providerUserId = "apple-sub")

        // when, then
        assertBusinessException(AuthErrorCode.OAUTH_AUTHORIZATION_CODE_REQUIRED) {
            userService.withdrawUser(userId, " ")
        }
        verify(exactly = 0) { userWithdrawalTransactionService.withdrawUser(any()) }
    }

    @Test
    fun `Apple 연결 끊기에 실패하면 탈퇴하지 않는다`() {
        // given
        givenUser(AuthProvider.APPLE, providerUserId = "apple-sub")
        every { appleOAuthClient.withdrawUser("apple-code", "apple-sub") } throws
            BusinessException(AuthErrorCode.INVALID_OAUTH_AUTHORIZATION_CODE)

        // when, then
        assertBusinessException(AuthErrorCode.INVALID_OAUTH_AUTHORIZATION_CODE) {
            userService.withdrawUser(userId, "apple-code")
        }
        verify(exactly = 0) { userWithdrawalTransactionService.withdrawUser(any()) }
        verify(exactly = 0) { tokenInvalidationRepository.createInvalidatedAt(any(), any()) }
        verify(exactly = 0) { imageService.deleteUserImages(any()) }
    }

    @Test
    fun `Apple이 아닌 계정은 authorizationCode를 보내도 Apple을 부르지 않고 탈퇴한다`() {
        // given
        givenUser(AuthProvider.KAKAO, providerUserId = "kakao-sub")
        givenWithdrawal()

        // when
        userService.withdrawUser(userId, "apple-code")

        // then
        verify(exactly = 0) { appleOAuthClient.withdrawUser(any(), any()) }
        verify(exactly = 1) { userWithdrawalTransactionService.withdrawUser(userId) }
    }

    @Test
    fun `이미 탈퇴한 사용자는 Apple을 부르지 않고 USER_NOT_FOUND로 거절한다`() {
        // given
        givenUser(AuthProvider.APPLE, providerUserId = "apple-sub").withdraw(clock.instant())

        // when, then
        assertBusinessException(UserErrorCode.USER_NOT_FOUND) {
            userService.withdrawUser(userId, "apple-code")
        }
        verify(exactly = 0) { appleOAuthClient.withdrawUser(any(), any()) }
        verify(exactly = 0) { userWithdrawalTransactionService.withdrawUser(any()) }
    }

    // ---------------------------------------------------------------- 픽스처

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    private fun givenUser(provider: AuthProvider, providerUserId: String): User {
        val user = User(provider = provider, providerUserId = providerUserId)
        every { userRepository.findByIdOrNull(userId) } returns user
        return user
    }

    private fun givenWithdrawal() {
        justRun { userWithdrawalTransactionService.withdrawUser(userId) }
        every { imageService.deleteUserImages(userId) } returns 0
    }

    private fun givenProfile(): Profile {
        val profile = Profile(
            nickname = "나비집사",
            birth = LocalDate.of(2000, 1, 1),
            gender = Gender.FEMALE,
            height = 160,
            weight = 50,
            userId = userId,
        )

        every { profileRepository.findProfileByUserId(userId) } returns profile

        return profile
    }
}
