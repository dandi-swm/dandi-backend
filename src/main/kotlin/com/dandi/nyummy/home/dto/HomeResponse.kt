package com.dandi.nyummy.home.dto

import com.dandi.nyummy.meal.dto.Streak
import com.dandi.nyummy.meal.dto.TodayMealSummary
import com.dandi.nyummy.user.dto.HomeUser

data class HomeResponse(val user: HomeUser, val streak: Streak, val todayMealSummary: TodayMealSummary)
