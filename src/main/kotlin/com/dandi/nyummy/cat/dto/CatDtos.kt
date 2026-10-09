package com.dandi.nyummy.cat.dto

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateCatRequest(
    @field:NotBlank(message = "고양이 이름은 필수입니다.")
    @field:Size(max = 20, message = "고양이 이름은 20자 이하여야 합니다.")
    val name: String,

    @field:Min(value = 0, message = "식사 시간은 0시 이상이어야 합니다.")
    @field:Max(value = 23, message = "식사 시간은 23시 이하여야 합니다.")
    val breakfastHour: Int? = null,

    @field:Min(value = 0, message = "식사 시간은 0시 이상이어야 합니다.")
    @field:Max(value = 23, message = "식사 시간은 23시 이하여야 합니다.")
    val lunchHour: Int? = null,

    @field:Min(value = 0, message = "식사 시간은 0시 이상이어야 합니다.")
    @field:Max(value = 23, message = "식사 시간은 23시 이하여야 합니다.")
    val dinnerHour: Int? = null,
) {
    /**
     * 끼니 시각이 겹치면 리마인더가 한 끼니만 발송된다.
     * MealReminderTarget.convertMealTime이 when의 첫 매치를 고르기 때문이다.
     */
    @get:AssertTrue(message = "식사 시각은 서로 달라야 합니다.")
    val isMealHoursDistinct: Boolean
        get() = listOfNotNull(breakfastHour, lunchHour, dinnerHour)
            .let { it.size == it.toSet().size }
}

data class CatResponse(
    val id: Long,
    val name: String,
    val weight: String,
    val weightName: String,
    val love: Int,
    val exp: Int,
)

data class CatAnimationResponse(val weight: String, val baseUrl: String, val animations: List<CatAnimation>)

data class CatAnimation(
    val state: String,
    val moment: String,
    val frame: Frame,
    val animation: List<List<AnimationSource>>,
    val text: List<String>,
)

data class Frame(val width: Int, val height: Int, val framesPerRow: Int, val durationMs: Int)

data class AnimationSource(val src: String, val frames: Int, val loop: Boolean = false)
