package com.dandi.nyummy.profile.mapper

import com.dandi.nyummy.auth.dto.SignUpRequest
import com.dandi.nyummy.profile.entity.Profile

fun SignUpRequest.toProfile(userId: Long) = Profile(
    userId = userId,
    nickname = this.nickname,
    birth = this.birth,
    gender = this.gender,
    height = this.height,
    weight = this.weight,
)
