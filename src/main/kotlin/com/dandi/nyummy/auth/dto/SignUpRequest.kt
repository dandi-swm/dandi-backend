package com.dandi.nyummy.auth.dto

import java.time.LocalDate

data class SignUpRequest(

    val email: String,

    val password: String,

    val nickname: String,

    val gender: String,

    val birth: LocalDate,

    val height: Int,

    val weight: Int,
)
