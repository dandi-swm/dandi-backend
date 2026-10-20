package com.dandi.nyummy.admin.controller

import com.dandi.nyummy.admin.config.AdminPaths
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminLoginRequest
import com.dandi.nyummy.admin.dto.AdminResponse
import com.dandi.nyummy.admin.dto.GoogleClientResponse
import com.dandi.nyummy.admin.service.AdminAuthService
import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.security.AdminTokenCookie
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Auth", description = "어드민 로그인 · 로그아웃 API (어드민 서비스 전용)")
@RestController
@RequestMapping("${AdminPaths.PREFIX}/auth")
@SecurityRequirements
@ConditionalOnAdminEnabled
class AdminAuthController(private val adminAuthService: AdminAuthService) {

    @Operation(
        summary = "Google 클라이언트 ID 조회",
        description = "어드민 웹이 로그인 전에 Google 로그인 버튼을 그릴 때 쓰는 클라이언트 ID를 조회한다. 공개 값이다.",
    )
    @GetMapping("/google-client")
    fun getGoogleClient(): GoogleClientResponse = adminAuthService.getGoogleClient()

    @Operation(
        summary = "어드민 로그인",
        description = "어드민 웹에서 받은 Google ID 토큰으로 로그인한다. 어드민 토큰은 httpOnly 쿠키(admin_token)로 내려가고 " +
            "본문에는 어드민 정보만 담긴다.",
    )
    @ApiErrorCodes(
        auth = [AuthErrorCode.INVALID_OAUTH_TOKEN, AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE],
    )
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: AdminLoginRequest): ResponseEntity<AdminResponse> {
        val result = adminAuthService.login(request)

        return ResponseEntity.ok()
            .header(
                HttpHeaders.SET_COOKIE,
                AdminTokenCookie.createCookie(result.adminToken, result.timeToLive).toString(),
            )
            .body(result.admin)
    }

    @Operation(summary = "어드민 로그아웃", description = "어드민 토큰 쿠키를 지운다. 쿠키가 없거나 만료돼도 성공한다.")
    @ApiResponse(responseCode = "204", description = "로그아웃 성공")
    @PostMapping("/logout")
    fun logout(): ResponseEntity<Void> = ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, AdminTokenCookie.createExpiredCookie().toString())
        .build()
}
