@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.admin.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant

// 앱 RichText가 아는 색 토큰. 모르는 값이면 앱이 contentPrimary로 그리므로 오타를 여기서 막는다.
private const val SDUI_COLOR_PATTERN = "contentPrimary|contentSecondary|contentTertiary|contentPromo|contentOnInverse"
private const val SDUI_COLOR_MESSAGE =
    "색은 contentPrimary, contentSecondary, contentTertiary, contentPromo, contentOnInverse 중 하나여야 합니다."

/**
 * 배너 만들기 요청. 이미지는 multipart의 다른 칸(image)으로 받는다.
 *
 * @property promotionId 알리는 프로모션. 만든 뒤에는 바꾸지 않는다
 * @property description 비우거나 공백만 있으면 설명 없이 만든다
 * @property titleColor 배너 그림에 맞춘 색 토큰. 어두운 그림 위에서는 contentOnInverse
 * @property displayOrder 추천 탭 노출 순서. 작은 값이 앞이다
 * @property endsAt null이면 내릴 때까지 노출한다
 */
data class CreateShopBannerRequest(
    val promotionId: Long,

    @field:NotBlank(message = "배너 제목은 필수입니다.")
    @field:Size(max = 100, message = "배너 제목은 100자 이하여야 합니다.")
    val title: String,

    @field:Size(max = 255, message = "배너 설명은 255자 이하여야 합니다.")
    val description: String?,

    @field:Pattern(regexp = SDUI_COLOR_PATTERN, message = SDUI_COLOR_MESSAGE)
    val titleColor: String,

    @field:Pattern(regexp = SDUI_COLOR_PATTERN, message = SDUI_COLOR_MESSAGE)
    val descriptionColor: String?,

    @field:PositiveOrZero(message = "노출 순서는 0 이상이어야 합니다.")
    val displayOrder: Int,

    val startsAt: Instant,

    val endsAt: Instant?,
)

/**
 * 배너 수정(PUT) 요청. 보낸 값으로 통째로 바꾸므로 모두 보낸다. 알리는 프로모션과 이미지는 바꾸지 않는다.
 * Kotlin DTO는 키가 없는 것과 null을 구분하지 못해 [description]·[descriptionColor]·[endsAt]을 빼면 null로 받는다.
 *
 * @property endsAt 배너를 내릴 때는 지금 시각을 보낸다
 */
data class UpdateShopBannerRequest(
    @field:NotBlank(message = "배너 제목은 필수입니다.")
    @field:Size(max = 100, message = "배너 제목은 100자 이하여야 합니다.")
    val title: String,

    @field:Size(max = 255, message = "배너 설명은 255자 이하여야 합니다.")
    val description: String?,

    @field:Pattern(regexp = SDUI_COLOR_PATTERN, message = SDUI_COLOR_MESSAGE)
    val titleColor: String,

    @field:Pattern(regexp = SDUI_COLOR_PATTERN, message = SDUI_COLOR_MESSAGE)
    val descriptionColor: String?,

    @field:PositiveOrZero(message = "노출 순서는 0 이상이어야 합니다.")
    val displayOrder: Int,

    val startsAt: Instant,

    val endsAt: Instant?,
)

data class AdminShopBannersResponse(val shopBanners: List<AdminShopBannerResponse>)

/**
 * @property bannerId 앱이 받는 bannerId와 같다
 * @property promotionName 알리는 프로모션의 이름
 * @property imageUrl CDN 주소 + imageKey
 * @property adminId 마지막으로 바꾼 운영자
 */
data class AdminShopBannerResponse(
    val bannerId: Long,
    val promotionId: Long,
    val promotionName: String,
    val imageUrl: String,
    val title: String,
    val description: String?,
    val titleColor: String,
    val descriptionColor: String?,
    val displayOrder: Int,
    val startsAt: Instant,
    val endsAt: Instant?,
    val adminId: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)
