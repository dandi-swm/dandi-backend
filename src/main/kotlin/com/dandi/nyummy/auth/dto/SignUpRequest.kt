package com.dandi.nyummy.auth.dto

import kotlin.time.Instant

data class SignUpRequest(

    val email: String,

    val password: String,

    val nickname: String,

    val gender: String,

    val birth: Instant,

    val height: Int,

    val weight: Int,
)
