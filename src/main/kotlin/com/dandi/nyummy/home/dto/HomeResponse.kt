package com.dandi.nyummy.home.dto

import com.dandi.nyummy.meal.dto.StreakResponse
import com.dandi.nyummy.meal.dto.TodayMealSummaryResponse
import com.dandi.nyummy.room.dto.EquippedRoomItemResponse
import com.dandi.nyummy.user.dto.HomeUserResponse

data class HomeResponse(
    val user: HomeUserResponse,
    val streak: StreakResponse,
    val todayMealSummary: TodayMealSummaryResponse,
    val hasUnreadInquiryAnswer: Boolean,
    val equippedRoomItems: List<EquippedRoomItemResponse>,
)
