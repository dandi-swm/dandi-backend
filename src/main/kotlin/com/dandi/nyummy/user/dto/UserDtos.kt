@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.user.dto

import com.dandi.nyummy.user.enum.Gender
import java.time.LocalDate

data class UserResponse(
    val id: Long,
    val email: String?,
    val nickname: String?,
    val birth: LocalDate?,
    val gender: Gender?,
    val height: Int?,
    val weight: Int?,
    val coin: Int,
    val breakfastHour: Int?,
    val lunchHour: Int?,
    val dinnerHour: Int?,
)

data class HomeUser(val coin: Int)

data class PasswordUpdateRequest(val password: String, val newPassword: String)
