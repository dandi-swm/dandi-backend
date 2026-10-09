@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.user.dto

import com.dandi.nyummy.user.enum.Gender
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
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

data class PasswordUpdateRequest(
    @field:NotBlank
    val currentPassword: String,

    @field:NotBlank
    @field:Size(min = 8, max = 64)
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$",
        message = "비밀번호는 영문과 숫자를 모두 포함해야 합니다.",
    )
    val newPassword: String,

    @field:NotBlank
    val confirmNewPassword: String,
) {
    @get:AssertTrue(message = "새 비밀번호가 일치하지 않습니다.")
    val isPasswordConfirmed: Boolean
        get() = newPassword == confirmNewPassword
}

data class PasswordUpdateResponse(val accessToken: String, val refreshToken: String)

/**
 * 알림 수신 설정 변경 요청. 바꾸려는 항목만 담고 나머지는 생략한다(생략 = null = 유지).
 * 두 플래그를 한 요청으로 받는 이유는 설정 화면이 토글 두 개를 같이 저장하기 때문이다.
 */
data class UpdatePushSettingRequest(
    val isServicePushEnabled: Boolean? = null,
    val isMarketingPushEnabled: Boolean? = null,
)

data class PushSettingResponse(val isServicePushEnabled: Boolean, val isMarketingPushEnabled: Boolean)

data class UpdateMealTimeRequest(
    @field:Min(value = 0, message = "식사 시간은 0시 이상이어야 합니다.")
    @field:Max(value = 23, message = "식사 시간은 23시 이하여야 합니다.")
    val breakfastHour: Int?,

    @field:Min(value = 0, message = "식사 시간은 0시 이상이어야 합니다.")
    @field:Max(value = 23, message = "식사 시간은 23시 이하여야 합니다.")
    val lunchHour: Int?,

    @field:Min(value = 0, message = "식사 시간은 0시 이상이어야 합니다.")
    @field:Max(value = 23, message = "식사 시간은 23시 이하여야 합니다.")
    val dinnerHour: Int?,
) {
    /**
     * 끼니 시각이 겹치면 리마인더가 한 끼니만 발송된다.
     * MealReminderTarget.convertMealTime이 when의 첫 매치를 고르기 때문이다.
     */
    @get:AssertTrue(message = "식사 시각은 서로 달라야 합니다.")
    val isMealHoursDistinct: Boolean
        get() = listOfNotNull(breakfastHour, lunchHour, dinnerHour)
            .let { it.size == it.toSet().size }
}
