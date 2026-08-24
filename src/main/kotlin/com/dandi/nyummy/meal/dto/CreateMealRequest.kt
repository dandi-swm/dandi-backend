package com.dandi.nyummy.meal.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant

data class CreateMealRequest(

    @field:NotBlank
    val imageKey: String,

    @field:NotNull
    val mealAt: Instant,

)
