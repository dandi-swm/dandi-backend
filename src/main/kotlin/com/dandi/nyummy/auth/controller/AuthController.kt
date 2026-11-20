package com.dandi.nyummy.auth.controller

import com.dandi.nyummy.auth.dto.LoginRequest
import com.dandi.nyummy.auth.dto.LoginResponse
import com.dandi.nyummy.auth.dto.OAuthLoginRequest
import com.dandi.nyummy.auth.dto.OAuthLoginResponse
import com.dandi.nyummy.auth.dto.RefreshRequest
import com.dandi.nyummy.auth.dto.RefreshResponse
import com.dandi.nyummy.auth.dto.ResetPasswordRequest
import com.dandi.nyummy.auth.dto.SendAuthCodeRequest
import com.dandi.nyummy.auth.dto.SendAuthCodeResponse
import com.dandi.nyummy.auth.dto.SignUpRequest
import com.dandi.nyummy.auth.dto.SignUpResponse
import com.dandi.nyummy.auth.dto.ValidateAuthCodeRequest
import com.dandi.nyummy.auth.dto.ValidateAuthCodeResponse
import com.dandi.nyummy.auth.service.AuthService
import com.dandi.nyummy.auth.service.OAuthService
import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.EmailErrorCode
import com.dandi.nyummy.language.CurrentLanguage
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Auth", description = "회원가입 · 로그인 · 로그아웃 · 이메일 인증 · 소셜 로그인 API")
@RestController
@RequestMapping("/api/v1/auth")
@SecurityRequirements
class AuthController(private val authService: AuthService, private val oauthService: OAuthService) {

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인하고 AccessToken(30분)과 RefreshToken(15일)을 발급받는다.")
    @ApiErrorCodes(auth = [AuthErrorCode.INVALID_CREDENTIALS])
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse = authService.login(request)

    @Operation(
        summary = "회원가입",
        description = "이메일 인증 확인 또는 소셜 로그인 응답의 verifiedToken과 닉네임으로 회원가입하고 " +
            "AccessToken과 RefreshToken을 발급받는다. password·confirmPassword는 이메일 가입 전용(필수)이며 소셜 가입은 생략한다. " +
            "신체 정보(gender·birth·height·weight)는 선택 입력이다.",
    )
    @ApiResponse(responseCode = "201", description = "회원가입 성공")
    @ApiErrorCodes(
        auth = [
            AuthErrorCode.VERIFICATION_EXPIRED,
            AuthErrorCode.INVALID_VERIFIED_TOKEN,
            AuthErrorCode.PASSWORD_REQUIRED,
            AuthErrorCode.EMAIL_ALREADY_EXISTS,
            AuthErrorCode.OAUTH_ACCOUNT_ALREADY_EXISTS,
        ],
    )
    @PostMapping("/signup")
    fun signUp(@Valid @RequestBody request: SignUpRequest): ResponseEntity<SignUpResponse> {
        val response = authService.signUp(request)

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response)
    }

    @Operation(
        summary = "소셜 로그인",
        description = "앱 SDK로 받은 소셜 제공자의 토큰(OIDC 제공자는 ID 토큰, 그 외는 access token)을 검증한다. " +
            "기존 회원이면 AccessToken·RefreshToken을 발급하고 redirectUrl은 홈, " +
            "신규 회원이면 verifiedToken을 발급하고 redirectUrl은 프로필 입력 화면이다. " +
            "nonce는 OIDC 제공자(KAKAO, GOOGLE, APPLE)에서 필수이며 앱이 SDK 로그인 시 전달한 값 그대로 보낸다. " +
            "NAVER는 token에 access token을 보내고 nonce는 생략한다. provider에 EMAIL은 보낼 수 없다.",
    )
    @ApiResponse(
        responseCode = "200",
        description = "기존 회원: accessToken·refreshToken / 신규 회원: verifiedToken (회원가입 API로 전달)",
    )
    @ApiErrorCodes(
        auth = [
            AuthErrorCode.UNSUPPORTED_OAUTH_PROVIDER,
            AuthErrorCode.OAUTH_NONCE_REQUIRED,
            AuthErrorCode.INVALID_OAUTH_TOKEN,
            AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE,
        ],
    )
    @PostMapping("/oauth/login")
    fun loginOAuth(@Valid @RequestBody request: OAuthLoginRequest): OAuthLoginResponse = oauthService.login(request)

    @Operation(summary = "토큰 재발급", description = "리프레시 토큰을 검증하고 AccessToken·RefreshToken을 새로 발급한다(rotate).")
    @ApiErrorCodes(auth = [AuthErrorCode.INVALID_REFRESH_TOKEN])
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): RefreshResponse = authService.refresh(request)

    @Operation(
        summary = "이메일 인증 코드 발송",
        description = "입력한 이메일 주소로 인증 코드를 발송한다. " +
            "purpose가 SIGNUP이면 미가입 이메일, RESET_PASSWORD면 가입된 이메일이어야 한다.",
    )
    @ApiResponse(responseCode = "201", description = "인증 코드 발송 성공. emailChallengeToken을 인증 코드 확인 API로 전달한다.")
    @ApiErrorCodes(
        auth = [
            AuthErrorCode.EMAIL_ALREADY_EXISTS,
            AuthErrorCode.EMAIL_NOT_FOUND,
            AuthErrorCode.EMAIL_SEND_RATE_LIMITED,
        ],
        email = [EmailErrorCode.EMAIL_SEND_FAILED],
    )
    @PostMapping("/email-verification")
    fun sendAuthCode(
        @CurrentLanguage language: Language,
        @Valid @RequestBody request: SendAuthCodeRequest,
    ): ResponseEntity<SendAuthCodeResponse> {
        val response = authService.sendAuthCode(request, language)

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response)
    }

    @Operation(
        summary = "이메일 인증 코드 확인",
        description = "이메일로 받은 인증 코드가 유효한지 검증한다. " +
            "검증 후 각 용도(회원가입·비밀번호 찾기)를 승계한 verifiedToken을 응답한다.",
    )
    @ApiResponse(responseCode = "200", description = "verifiedToken 발급")
    @ApiErrorCodes(
        auth = [
            AuthErrorCode.EMAIL_CODE_EXPIRED,
            AuthErrorCode.INVALID_EMAIL_CHALLENGE_TOKEN,
            AuthErrorCode.INCORRECT_EMAIL,
            AuthErrorCode.EMAIL_CODE_ATTEMPT_EXCEEDED,
            AuthErrorCode.EMAIL_CODE_MISMATCH,
        ],
    )
    @PostMapping("/email-verification/confirm")
    fun validateAuthCode(
        @Valid @RequestBody request: ValidateAuthCodeRequest,
    ): ResponseEntity<ValidateAuthCodeResponse> {
        val response = authService.validateAuthCode(request)

        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "비밀번호 재설정",
        description = "비밀번호 찾기 용도의 verifiedToken을 검증하고, " +
            "임시 비밀번호로 교체한 뒤 이메일로 발송한다.",
    )
    @ApiResponse(responseCode = "204", description = "임시 비밀번호 발송 완료")
    @ApiErrorCodes(
        auth = [
            AuthErrorCode.VERIFICATION_EXPIRED,
            AuthErrorCode.INVALID_VERIFIED_TOKEN,
            AuthErrorCode.EMAIL_NOT_FOUND,
        ],
        email = [EmailErrorCode.EMAIL_SEND_FAILED],
    )
    @PostMapping("/password/reset")
    fun resetPassword(
        @CurrentLanguage language: Language,
        @Valid @RequestBody request: ResetPasswordRequest,
    ): ResponseEntity<Void> {
        authService.resetPassword(request, language)

        return ResponseEntity
            .noContent()
            .build()
    }

    @Operation(
        summary = "로그아웃",
        description = "저장된 RefreshToken과 디바이스 토큰을 삭제해 로그아웃 처리한다. " +
            "디바이스 토큰을 함께 지우는 이유는 로그아웃한 기기로 푸시가 계속 가지 않게 하기 위해서다. " +
            "저장된 토큰이 없어도 이미 로그아웃된 상태로 보고 정상 처리한다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "로그아웃 성공")
    @PostMapping("/logout")
    fun logout(@CurrentUser user: AuthUser): ResponseEntity<Void> {
        authService.logout(user.userId)

        return ResponseEntity
            .noContent()
            .build()
    }
}
