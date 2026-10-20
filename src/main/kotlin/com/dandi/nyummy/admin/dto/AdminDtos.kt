package com.dandi.nyummy.admin.dto

import jakarta.validation.constraints.NotBlank
import java.time.Duration

data class AdminLoginRequest(
    @field:NotBlank
    val idToken: String,
)

data class AdminResponse(val adminId: Long, val email: String, val name: String)

/**
 * 로그인 결과. 토큰은 컨트롤러가 쿠키로 싣고, 응답 본문에는 어드민 정보만 내린다.
 *
 * @property adminToken 쿠키에 담을 어드민 JWT
 * @property timeToLive 쿠키 수명. 토큰 수명과 맞춘다
 */
data class AdminLoginResult(val adminToken: String, val timeToLive: Duration, val admin: AdminResponse)

/** 어드민 웹용 Google OAuth 클라이언트 ID. 공개 값이다. */
data class GoogleClientResponse(val clientId: String)
