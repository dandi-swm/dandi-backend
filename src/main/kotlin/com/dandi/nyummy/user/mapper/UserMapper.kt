package com.dandi.nyummy.user.mapper

import com.dandi.nyummy.auth.dto.SignUpRequest
import com.dandi.nyummy.user.entity.User

fun SignUpRequest.toUser(encodedPassword: String) = User(
    email = this.email,
    password = encodedPassword,
)
