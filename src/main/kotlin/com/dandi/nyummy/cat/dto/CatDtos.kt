package com.dandi.nyummy.cat.dto

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
)

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
