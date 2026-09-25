package com.dandi.nyummy.auth.dto

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.auth.enum.AuthPurpose
import com.dandi.nyummy.profile.enum.Gender
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Past
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.LocalDate

data class SignUpRequest(

    @field:NotBlank
    val emailVerifiedToken: String,

    @field:NotBlank
    @field:Size(min = 8, max = 64)
    @field:Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "비밀번호는 영문과 숫자를 모두 포함해야 합니다.")
    val password: String,

    @field:NotBlank
    val confirmPassword: String,

    @field:NotBlank
    @field:Size(max = 100)
    val nickname: String,

    val gender: Gender? = null,

    @field:Past
    val birth: LocalDate? = null,

    @field:Positive
    val height: Int? = null,

    @field:Positive
    val weight: Int? = null,
) {
    @get:AssertTrue(message = "비밀번호가 일치하지 않습니다.")
    val isPasswordConfirmed: Boolean
        get() = password == confirmPassword
}

data class SignUpResponse(val accessToken: String, val refreshToken: String)

data class LoginRequest(

    @field:NotBlank
    @field:Email
    val email: String,

    @field:NotBlank
    val password: String,
)

data class LoginResponse(val redirectUrl: String, val accessToken: String, val refreshToken: String)

data class RefreshRequest(

    @field:NotBlank
    val refreshToken: String,

)

data class RefreshResponse(val accessToken: String, val refreshToken: String)

data class SendAuthCodeRequest(

    @field:Email
    @field:NotBlank
    val email: String,

    val purpose: AuthPurpose,
)

data class SendAuthCodeResponse(val emailChallengeToken: String)

data class ConfirmAuthCodeRequest(

    @field:NotBlank
    @field:Pattern(regexp = "^\\d{6}$")
    val authCode: String,

    @field:NotBlank
    val emailChallengeToken: String,
)

data class ConfirmAuthCodeResponse(val emailVerifiedToken: String)

data class PasswordResetRequest(

    @field:NotBlank
    val emailVerifiedToken: String,
)

data class OAuthLoginRequest(

    val provider: AuthProvider,

    @field:NotBlank
    val idToken: String,

    @field:NotBlank
    val nonce: String,
)

data class OAuthLoginResponse(
    val redirectUrl: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val oauthVerifiedToken: String? = null,
)

data class OAuthSignUpRequest(

    @field:NotBlank
    val oauthVerifiedToken: String,

    @field:NotBlank
    @field:Size(max = 100)
    val nickname: String,

    val gender: Gender? = null,

    @field:Past
    val birth: LocalDate? = null,

    @field:Positive
    val height: Int? = null,

    @field:Positive
    val weight: Int? = null,
)
