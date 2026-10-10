package com.dandi.nyummy.image.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 모든 [com.dandi.nyummy.image.enum.ImagePurpose]에 공통인 이미지 설정.
 * 용도별로 달라지는 값은 ImagePurpose에 둔다.
 */
@ConfigurationProperties("app.image")
data class ImageProperties(
    val uploadMethod: String,
    val uploadUrlExpirationMinutes: Int,
    val imageUrlExpirationMinutes: Int,
)
