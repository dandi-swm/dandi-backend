package com.dandi.nyummy.user.service

import com.dandi.nyummy.auth.repository.TokenInvalidationRepository
import com.dandi.nyummy.auth.service.RefreshTokenService
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.security.jwt.JwtService
import com.dandi.nyummy.user.dto.UpdateProfileRequest
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.enum.Gender
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserServiceTest {

    private val profileRepository = mockk<ProfileRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-11-03T03:00:00Z"), ZoneId.of("Asia/Seoul"))

    private val userId = 1L

    private val userService = UserService(
        userRepository = mockk<UserRepository>(),
        profileRepository = profileRepository,
        passwordService = mockk<PasswordService>(),
        jwtService = mockk<JwtService>(),
        refreshTokenService = mockk<RefreshTokenService>(),
        tokenInvalidationRepository = mockk<TokenInvalidationRepository>(),
        clock = clock,
        userWithdrawalTransactionService = mockk<UserWithdrawalTransactionService>(),
        imageService = mockk<ImageService>(),
        coinService = mockk<CoinService>(),
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

    // ---------------------------------------------------------------- 픽스처

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
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
