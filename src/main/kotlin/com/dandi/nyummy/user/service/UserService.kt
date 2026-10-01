package com.dandi.nyummy.user.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.user.dto.UserResponse
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.mapper.toUserResponse
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(private val userRepository: UserRepository, private val profileRepository: ProfileRepository) {
    @Transactional(readOnly = true)
    fun getMe(id: Long): UserResponse {
        val user = userRepository.findByIdOrNull(id)
            ?: throw BusinessException(UserErrorCode.USER_NOT_FOUND)

        val profile = profileRepository.getProfileByUserId(user.id)
            ?: throw BusinessException(UserErrorCode.USER_NOT_FOUND)

        return user.toUserResponse(profile)
    }
}
