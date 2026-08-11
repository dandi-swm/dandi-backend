package com.dandi.nyummy.auth.controller

import com.dandi.nyummy.auth.dto.EmailVerificationConfirmRequest
import com.dandi.nyummy.auth.dto.EmailVerificationRequest
import com.dandi.nyummy.auth.dto.LoginRequest
import com.dandi.nyummy.auth.dto.LoginResponse
import com.dandi.nyummy.auth.dto.SignUpRequest
import com.dandi.nyummy.auth.dto.SignUpResponse
import com.dandi.nyummy.auth.service.AuthService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Auth", description = "회원가입 · 로그인 · 이메일 인증 API (인증 토큰 불필요)")
@RestController
@RequestMapping("/api/v1/auth")
@SecurityRequirements
class AuthController(private val authService: AuthService) {

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

    @Operation(summary = "이메일 인증 코드 발송", description = "입력한 이메일 주소로 인증 코드를 발송한다.")
    @PostMapping("/email-verification")
    fun emailVerification(@RequestBody request: EmailVerificationRequest) = ResponseEntity.ok()

    @Operation(summary = "이메일 인증 코드 확인", description = "이메일로 받은 인증 코드가 유효한지 검증한다.")
    @PostMapping("/email-verification/confirm")
    fun emailVerificationConfirm(@RequestBody request: EmailVerificationConfirmRequest) = ResponseEntity.ok()
}
