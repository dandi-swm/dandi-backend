package com.dandi.nyummy.cat.dto

import com.dandi.nyummy.cat.enum.CatWeight
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class CreateCatRequest(
    // 길이는 앱이 구분할 수 있게 서비스에서 검사한다(api.cat.nameTooLong). @Size는 api.common.invalidInputValue로 나간다.
    @field:NotBlank(message = "고양이 이름은 필수입니다.")
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
     * MealReminderTargetResult.convertMealTime이 when의 첫 매치를 고르기 때문이다.
     */
    @get:AssertTrue(message = "식사 시각은 서로 달라야 합니다.")
    @get:Schema(hidden = true)
    val isMealHoursDistinct: Boolean
        get() = listOfNotNull(breakfastHour, lunchHour, dinnerHour)
            .let { it.size == it.toSet().size }
}

/**
 * 고양이 변경 요청. 바꾸려는 항목만 담고 나머지는 생략한다(생략 = null = 유지).
 */
data class UpdateCatRequest(
    // @NotBlank는 null도 거부해 생략을 막는다. @Pattern은 null을 통과시키고 공백만 있는 이름을 거부한다.
    // 길이는 생성과 같이 서비스에서 검사한다(api.cat.nameTooLong).
    @field:Pattern(regexp = "(?s).*\\S.*", message = "고양이 이름은 공백일 수 없습니다.")
    val name: String? = null,
)

data class CatResponse(
    val id: Long,
    val name: String,
    val weight: CatWeight,
    val weightName: String,
    val love: Int,
    val exp: Int,
)

data class CatAnimationResponse(val weight: String, val baseUrl: String, val animations: List<CatAnimationItemResponse>)

data class CatAnimationItemResponse(
    val state: String,
    val moment: String,
    val frame: FrameResponse,
    val animation: List<List<AnimationSourceResponse>>,
    val text: List<String>,
)

data class FrameResponse(val width: Int, val height: Int, val framesPerRow: Int, val durationMs: Int)

data class AnimationSourceResponse(val src: String, val frames: Int, val loop: Boolean = false)
