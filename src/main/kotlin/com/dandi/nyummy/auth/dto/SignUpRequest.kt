package com.dandi.nyummy.auth.dto

import com.dandi.nyummy.profile.enum.Gender
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Past
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.LocalDate

data class SignUpRequest(

    @field:Email
    @field:NotBlank
    val email: String,

    @field:NotBlank
    @field:Size(min = 8, max = 64)
    val password: String,

    @field:NotBlank
    @field:Size(max = 100)
    val nickname: String,

    val gender: Gender,

    @field:Past
    val birth: LocalDate,

    @field:Positive
    val height: Int,

    @field:Positive
    val weight: Int,
)
