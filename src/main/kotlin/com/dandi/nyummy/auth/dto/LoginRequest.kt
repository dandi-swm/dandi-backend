package com.dandi.nyummy.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class LoginRequest(

    @field:NotBlank
    @field:Email
    val email: String,

    @field:NotBlank
    val password: String,
)
