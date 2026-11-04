package com.dandi.nyummy.user.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.auth.repository.RefreshTokenRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.inquiry.repository.InquiryRepository
import com.dandi.nyummy.notification.repository.DeviceTokenRepository
import com.dandi.nyummy.notification.repository.PushHistoryRepository
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.user.repository.UserRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserWithdrawalTransactionServiceTest {

    private val userId = 1L
    private val now = Instant.parse("2026-11-03T03:00:00Z")

    private val userRepository = mockk<UserRepository>()
    private val profileRepository = mockk<ProfileRepository>()
    private val refreshTokenRepository = mockk<RefreshTokenRepository>()
    private val deviceTokenRepository = mockk<DeviceTokenRepository>()
    private val inquiryRepository = mockk<InquiryRepository>()
    private val pushHistoryRepository = mockk<PushHistoryRepository>()

    private val userWithdrawalTransactionService = UserWithdrawalTransactionService(
        userRepository = userRepository,
        profileRepository = profileRepository,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        refreshTokenRepository = refreshTokenRepository,
        deviceTokenRepository = deviceTokenRepository,
        inquiryRepository = inquiryRepository,
        pushHistoryRepository = pushHistoryRepository,
    )

    @Test
    fun `탈퇴하면 전송 내역을 지운다`() {
        every { userRepository.findById(userId) } returns Optional.of(User(provider = AuthProvider.EMAIL))
        every { profileRepository.findProfileByUserId(userId) } returns Profile(userId = userId)
        every { refreshTokenRepository.deleteByUserId(any()) } just runs
        every { deviceTokenRepository.deleteByUserId(userId) } just runs
        every { inquiryRepository.deleteByUserId(userId) } returns 0
        every { pushHistoryRepository.deleteByUserId(userId) } returns 1

        userWithdrawalTransactionService.withdrawUser(userId)

        verify(exactly = 1) { pushHistoryRepository.deleteByUserId(userId) }
    }

    @Test
    fun `이미 탈퇴한 사용자면 거부하고 전송 내역을 지우지 않는다`() {
        val user = User(provider = AuthProvider.EMAIL).apply { withdraw(now) }
        every { userRepository.findById(userId) } returns Optional.of(user)

        assertBusinessException(UserErrorCode.USER_NOT_FOUND) {
            userWithdrawalTransactionService.withdrawUser(userId)
        }

        verify(exactly = 0) { pushHistoryRepository.deleteByUserId(any()) }
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }
}
