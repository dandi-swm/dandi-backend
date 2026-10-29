package com.dandi.nyummy.shop.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * @property cdnBaseUrl 배너 그림 주소를 만들 CDN 주소. 배너 imageUrl은 이 주소 + `shop_banners.image_key`다
 * @property newUserTimeToLive 가입 후 이 기간 동안 신입 집사([com.dandi.nyummy.shop.enum.PromotionCategory.NEW_USER]) 프로모션 대상이다
 * @property newProductTimeToLive 판매를 시작한 뒤 이 기간 동안 상품 카드에 신규 태그를 붙인다
 */
@ConfigurationProperties("app.shop")
data class ShopProperties(
    val cdnBaseUrl: String = "https://cdn.nyummy.co.kr",
    val newUserTimeToLive: Duration = Duration.ofDays(7),
    val newProductTimeToLive: Duration = Duration.ofDays(7),
)
