package com.dandi.nyummy.user.controller

import com.dandi.nyummy.user.dto.PasswordUpdateRequest
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
class UserController {

    @PatchMapping("/me/password")
    fun updatePassword(@RequestBody request: PasswordUpdateRequest, @CurrentUser user: Auth)
}
