package com.dandi.nyummy.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class EmailVerificationRequest(

    @field:Email
    @field:NotBlank
    val email: String,
)
