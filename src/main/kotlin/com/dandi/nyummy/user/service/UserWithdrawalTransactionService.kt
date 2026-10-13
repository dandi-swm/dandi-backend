package com.dandi.nyummy.user.service

import com.dandi.nyummy.auth.repository.RefreshTokenRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.inquiry.repository.InquiryRepository
import com.dandi.nyummy.notification.repository.DeviceTokenRepository
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class UserWithdrawalTransactionService(
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val clock: Clock,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val deviceTokenRepository: DeviceTokenRepository,
    private val inquiryRepository: InquiryRepository,
) {
    @Transactional
    fun withdrawUser(userId: Long) {
        val user = userRepository.findByIdOrNull(userId)
            ?: throw BusinessException(UserErrorCode.USER_NOT_FOUND)

        if (user.isWithdrawn) {
            throw BusinessException(UserErrorCode.USER_NOT_FOUND)
        }

        val profile = profileRepository.findProfileByUserId(userId)
            ?: throw BusinessException(UserErrorCode.PROFILE_NOT_FOUND)

        user.withdraw(Instant.now(clock))
        profile.anonymize()
        refreshTokenRepository.deleteByUserId(user.id)
        deviceTokenRepository.deleteByUserId(userId)
        inquiryRepository.deleteByUserId(userId)
    }
}
