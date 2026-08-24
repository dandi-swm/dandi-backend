package com.dandi.nyummy.meal.dto

import com.dandi.nyummy.meal.enum.MealStatus
import java.time.Instant

data class MealResponse(

    val mealId: Long,

    val name: String,

    val mealAt: Instant,

    val status: MealStatus,

    val nutrition: Nutrition,

    val imageUrl: String,

    val iconId: Long? = 1,
)
