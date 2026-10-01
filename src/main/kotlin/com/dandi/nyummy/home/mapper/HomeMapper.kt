package com.dandi.nyummy.home.mapper

import com.dandi.nyummy.user.dto.HomeUser
import com.dandi.nyummy.user.entity.Profile

fun Profile.toHomeUser() = HomeUser(coin = this.coin)
