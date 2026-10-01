package com.dandi.nyummy.user.controller

import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import com.dandi.nyummy.user.dto.PasswordUpdateRequest
import com.dandi.nyummy.user.dto.UserResponse
import com.dandi.nyummy.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
class UserController(private val userService: UserService) {
    @GetMapping("/me")
    fun me(@CurrentUser user: AuthUser): UserResponse = userService.getMe(user.userId)

    @PatchMapping("/me/password")
    fun updatePassword(@RequestBody request: PasswordUpdateRequest, @CurrentUser user: AuthUser) = ResponseEntity.ok()
}
