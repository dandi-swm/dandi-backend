package com.dandi.nyummy.user.mapper

import com.dandi.nyummy.user.dto.PushSettingResponse
import com.dandi.nyummy.user.dto.UserResponse
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.entity.User

fun User.toUserResponse(profile: Profile) = UserResponse(
    id = this.id,
    email = this.email,
    nickname = profile.nickname,
    birth = profile.birth,
    gender = profile.gender,
    height = profile.height,
    weight = profile.weight,
    coin = profile.coin,
    breakfastHour = profile.breakfastHour,
    lunchHour = profile.lunchHour,
    dinnerHour = profile.dinnerHour,
)

fun Profile.toPushSettingResponse() = PushSettingResponse(
    isServicePushEnabled = this.isServicePushEnabled,
    isMarketingPushEnabled = this.isMarketingPushEnabled,
)
