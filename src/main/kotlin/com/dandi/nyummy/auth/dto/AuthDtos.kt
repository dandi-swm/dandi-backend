package com.dandi.nyummy.auth.dto

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.auth.enum.AuthPurpose
import com.dandi.nyummy.user.enum.Gender
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Past
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * 이메일·소셜 공용 회원가입 요청. 신원은 verifiedToken에서 꺼내므로 이메일 필드가 없다.
 *
 * password·confirmPassword는 이메일 가입 전용이다 — 소셜 가입은 비워 보낸다.
 * 비밀번호 필수 여부는 토큰 안의 provider를 봐야 알 수 있어 서비스에서 검사한다(`@Size`·`@Pattern`은 null을 통과시킨다).
 */
data class SignUpRequest(

    @field:NotBlank
    val verifiedToken: String,

    @field:Size(min = 8, max = 64)
    @field:Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "비밀번호는 영문과 숫자를 모두 포함해야 합니다.")
    val password: String? = null,

    val confirmPassword: String? = null,

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

data class ConfirmAuthCodeResponse(val verifiedToken: String)

data class PasswordResetRequest(

    @field:NotBlank
    val verifiedToken: String,
)

/**
 * 소셜 로그인 요청. token의 의미는 제공자에 따라 다르다 — OIDC 제공자(Kakao·Google·Apple)는 ID 토큰,
 * Naver처럼 OIDC가 없는 제공자는 access token. nonce는 OIDC 제공자에서 필수(앱이 SDK 로그인 때 넘긴 값)이고 그 외엔 생략한다.
 */
data class OAuthLoginRequest(

    val provider: AuthProvider,

    @field:NotBlank
    val token: String,

    val nonce: String? = null,
)

data class OAuthLoginResponse(
    val redirectUrl: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val verifiedToken: String? = null,
)
