package com.dandi.nyummy.notification.controller

import com.dandi.nyummy.notification.dto.CreateDeviceTokenRequest
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val notificationService: NotificationService) {
    @PostMapping("/device-tokens")
    fun createDeviceToken(@CurrentUser user: AuthUser, @RequestBody request: CreateDeviceTokenRequest) {
        notificationService.createDeviceToken(user.userId, request)
    }
}
