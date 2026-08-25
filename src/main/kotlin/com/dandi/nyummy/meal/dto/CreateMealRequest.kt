package com.dandi.nyummy.meal.dto

import jakarta.validation.constraints.NotBlank

data class CreateMealRequest(

    @field:NotBlank
    val imageKey: String,

)
