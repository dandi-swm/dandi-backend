package com.dandi.nyummy.notification.controller

import com.dandi.nyummy.notification.dto.CreateDeviceTokenRequest
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Notification", description = "푸시 발송용 디바이스 토큰 등록 API")
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val notificationService: NotificationService) {

    @Operation(
        summary = "디바이스 토큰 등록",
        description = "푸시 발송에 쓸 FCM 토큰을 등록한다. 멀티 로그인을 허용하지 않으므로 사용자당 한 행만 두고, " +
            "같은 사용자가 다시 호출하면 토큰을 교체한다(멱등). 같은 토큰이 다른 사용자에 묶여 있으면 그 연결을 끊는다 — " +
            "로그아웃 없이 앱을 지운 뒤 같은 기기에서 다른 계정이 로그인하는 경우다. " +
            "토큰은 FCM이 갱신할 수 있으므로 앱 실행마다 호출해도 된다.",
    )
    @ApiResponse(responseCode = "200", description = "등록 완료")
    @ApiResponse(responseCode = "401", description = "인증이 필요합니다.")
    @PostMapping("/device-tokens")
    fun createDeviceToken(@CurrentUser user: AuthUser, @RequestBody request: CreateDeviceTokenRequest) {
        notificationService.createDeviceToken(user.userId, request)
    }
}
