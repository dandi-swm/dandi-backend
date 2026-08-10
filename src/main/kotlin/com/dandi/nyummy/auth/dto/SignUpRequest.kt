package com.dandi.nyummy.auth.dto

import com.dandi.nyummy.profile.enum.Gender
import java.time.LocalDate

data class SignUpRequest(

    val email: String,

    val password: String,

    val nickname: String,

    val gender: Gender,

    val birth: LocalDate,

    val height: Int,

    val weight: Int,
)
