package com.dandi.nyummy.security

import com.dandi.nyummy.admin.config.AdminPaths
import org.springframework.http.ResponseCookie
import java.time.Duration

/**
 * 어드민 토큰을 싣는 httpOnly 쿠키. 이름·속성을 발급(로그인)·삭제(로그아웃)·읽기(필터)가 함께 쓴다.
 *
 * - HttpOnly: JS가 읽을 수 없어 XSS가 생겨도 토큰이 밖으로 새지 않는다
 * - Secure: HTTPS로만 보낸다 (브라우저는 localhost를 예외로 허용한다)
 * - SameSite=Strict: 다른 사이트에서 시작된 요청에는 붙지 않는다
 * - Path: 어드민 API에만 붙고 앱 API에는 붙지 않는다
 */
object AdminTokenCookie {
    const val NAME = "admin_token"
    private const val PATH = AdminPaths.PREFIX

    fun createCookie(token: String, timeToLive: Duration): ResponseCookie = ResponseCookie.from(NAME, token)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path(PATH)
        .maxAge(timeToLive)
        .build()

    /** 같은 이름·경로로 수명 0인 쿠키를 내려 브라우저가 지우게 한다. httpOnly라 JS로는 지울 수 없다. */
    fun createExpiredCookie(): ResponseCookie = ResponseCookie.from(NAME, "")
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path(PATH)
        .maxAge(0)
        .build()
}
