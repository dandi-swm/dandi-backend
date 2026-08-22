package com.dandi.nyummy.auth.controller

import com.dandi.nyummy.auth.dto.EmailVerificationConfirmRequest
import com.dandi.nyummy.auth.dto.EmailVerificationRequest
import com.dandi.nyummy.auth.dto.LoginRequest
import com.dandi.nyummy.auth.dto.LoginResponse
import com.dandi.nyummy.auth.dto.RefreshRequest
import com.dandi.nyummy.auth.dto.RefreshResponse
import com.dandi.nyummy.auth.dto.SignUpRequest
import com.dandi.nyummy.auth.dto.SignUpResponse
import com.dandi.nyummy.auth.service.AuthService
import com.dandi.nyummy.auth.service.MailService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Auth", description = "회원가입 · 로그인 · 로그아웃 · 이메일 인증 API")
@RestController
@RequestMapping("/api/v1/auth")
@SecurityRequirements
class AuthController(private val mailService: MailService, private val authService: AuthService) {

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인하고 AccessToken(30분)과 RefreshToken(15일)을 발급받는다.")
    @ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호가 올바르지 않습니다.")
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse = authService.login(request)

    @Operation(summary = "회원가입", description = "이메일·비밀번호·닉네임과 신체 정보로 회원가입하고 AccessToken과 RefreshToken을 발급받는다.")
    @PostMapping("/signup")
    fun signup(@RequestBody request: SignUpRequest): ResponseEntity<SignUpResponse> = ResponseEntity.ok(
        SignUpResponse(
            accessToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIiwidXNlcklkIjoxLCJ0eXBlIjoiYWNjZXNzIiwia" +
                "WF0IjoxNzUzOTIwMDAwLCJleHAiOjE3NTM5MjM2MDB9.hm9KdG2zY6kA3OooLoNbUl4nwF56MJHh2ygSIq5iwHA",
            refreshToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIiwidXNlcklkIjoxLCJ0eXBlIjoicmVmcmVzaCIs" +
                "ImlhdCI6MTc1MzkyMDAwMCwiZXhwIjoxNzU1MTI5NjAwfQ.3dYJS1UPcP5ohWa4yPbdmwjd4rRwa7nSL3AadcgMFXM",
        ),
    )

    @Operation(summary = "토큰 재발급", description = "리프레시 토큰을 검증하고 AccessToken·RefreshToken을 새로 발급한다(rotate).")
    @ApiResponse(responseCode = "401", description = "유효하지 않은 리프레시 토큰입니다.")
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): RefreshResponse = authService.refresh(request)

    @Operation(summary = "이메일 인증 코드 발송", description = "입력한 이메일 주소로 인증 코드를 발송한다.")
    @PostMapping("/email-verification")
    fun emailVerification(@Valid @RequestBody request: EmailVerificationRequest): ResponseEntity<Void> {
        mailService.sendEmail(request.email)

        return ResponseEntity
            .noContent()
            .build()
    }

    @Operation(summary = "이메일 인증 코드 확인", description = "이메일로 받은 인증 코드가 유효한지 검증한다.")
    @PostMapping("/email-verification/confirm")
    fun emailVerificationConfirm(@Valid @RequestBody request: EmailVerificationConfirmRequest): ResponseEntity<Void> {
        mailService.confirm(request.email, request.verificationCode)

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
        authService.logout(user.userId, user.accessToken)

        return ResponseEntity
            .noContent()
            .build()
    }
}
