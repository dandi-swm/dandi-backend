package com.dandi.nyummy.auth.controller

import com.dandi.nyummy.auth.dto.ConfirmAuthCodeRequest
import com.dandi.nyummy.auth.dto.ConfirmAuthCodeResponse
import com.dandi.nyummy.auth.dto.LoginRequest
import com.dandi.nyummy.auth.dto.LoginResponse
import com.dandi.nyummy.auth.dto.PasswordResetRequest
import com.dandi.nyummy.auth.dto.RefreshRequest
import com.dandi.nyummy.auth.dto.RefreshResponse
import com.dandi.nyummy.auth.dto.SendAuthCodeRequest
import com.dandi.nyummy.auth.dto.SendAuthCodeResponse
import com.dandi.nyummy.auth.dto.SignUpRequest
import com.dandi.nyummy.auth.dto.SignUpResponse
import com.dandi.nyummy.auth.service.AuthService
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

@Tag(name = "Auth", description = "회원가입 · 로그인 · 로그아웃 · 이메일 인증 API")
@RestController
@RequestMapping("/api/v1/auth")
@SecurityRequirements
class AuthController(private val authService: AuthService) {

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인하고 AccessToken(30분)과 RefreshToken(15일)을 발급받는다.")
    @ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호가 올바르지 않습니다.")
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse = authService.login(request)

    @Operation(
        summary = "회원가입",
        description = "이메일 인증 토큰(emailVerifiedToken)과 비밀번호·닉네임으로 회원가입하고 " +
            "AccessToken과 RefreshToken을 발급받는다. 신체 정보(gender·birth·height·weight)는 선택 입력이다.",
    )
    @ApiResponse(responseCode = "409", description = "이미 가입된 이메일입니다.")
    @PostMapping("/signup")
    fun signup(@Valid @RequestBody request: SignUpRequest): ResponseEntity<SignUpResponse> {
        val response = authService.signup(request)

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response)
    }

    @Operation(summary = "토큰 재발급", description = "리프레시 토큰을 검증하고 AccessToken·RefreshToken을 새로 발급한다(rotate).")
    @ApiResponse(responseCode = "401", description = "유효하지 않은 리프레시 토큰입니다.")
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): RefreshResponse = authService.refresh(request)

    @Operation(
        summary = "이메일 인증 코드 발송",
        description = "입력한 이메일 주소로 인증 코드를 발송한다. " +
            "purpose가 SIGNUP이면 미가입 이메일, RESET_PASSWORD면 가입된 이메일이어야 한다.",
    )
    @ApiResponse(responseCode = "409", description = "이미 가입된 이메일입니다. (SIGNUP)")
    @ApiResponse(responseCode = "404", description = "가입되지 않은 이메일입니다. (RESET_PASSWORD)")
    @PostMapping("/email-verification")
    fun sendAuthCode(@Valid @RequestBody request: SendAuthCodeRequest): ResponseEntity<SendAuthCodeResponse> {
        val response = authService.sendAuthCode(request)

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response)
    }

    @Operation(
        summary = "이메일 인증 코드 확인",
        description = "이메일로 받은 인증 코드가 유효한지 검증한다. " +
            "검증 후 각 용도에 맞는 emailVerifiedToken을 응답한다.",
    )
    @ApiResponse(responseCode = "200", description = "emailVerifiedToken 발급")
    @PostMapping("/email-verification/confirm")
    fun confirmAuthCode(@Valid @RequestBody request: ConfirmAuthCodeRequest): ResponseEntity<ConfirmAuthCodeResponse> {
        val response = authService.confirmAuthCode(request)

        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "비밀번호 재설정",
        description = "비밀번호 찾기 용도의 emailVerifiedToken을 검증하고, " +
            "임시 비밀번호로 교체한 뒤 이메일로 발송한다.",
    )
    @ApiResponse(responseCode = "204", description = "임시 비밀번호 발송 완료")
    @ApiResponse(responseCode = "401", description = "토큰이 유효하지 않거나 비밀번호 찾기 용도가 아닙니다.")
    @PostMapping("/password/reset")
    fun resetPassword(@Valid @RequestBody request: PasswordResetRequest): ResponseEntity<Void> {
        authService.resetPassword(request)

        return ResponseEntity
            .noContent()
            .build()
    }

    @Operation(
        summary = "로그아웃",
        description = "저장된 RefreshToken을 삭제해 로그아웃 처리한다. " +
            "저장된 토큰이 없어도 이미 로그아웃된 상태로 보고 정상 처리한다. ",
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "로그아웃 성공")
    @ApiResponse(responseCode = "401", description = "인증이 필요합니다.")
    @PostMapping("/logout")
    fun logout(@CurrentUser user: AuthUser): ResponseEntity<Void> {
        authService.logout(user.userId)

        return ResponseEntity
            .noContent()
            .build()
    }
}
