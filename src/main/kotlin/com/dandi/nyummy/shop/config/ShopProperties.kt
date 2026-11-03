package com.dandi.nyummy.shop.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * @property cdnBaseUrl 배너 그림 주소를 만들 CDN 주소. 배너 imageUrl은 이 주소 + `shop_banners.image_key`다
 * @property newUserTimeToLive 가입 후 이 기간 동안 신입 집사([com.dandi.nyummy.shop.enum.PromotionCategory.NEW_USER]) 프로모션 대상이다
 * @property newProductTimeToLive 판매를 시작한 뒤 이 기간 동안 상품 카드에 신규 태그를 붙인다
 * @property dailyDiscountRate 배치가 만드는 오늘의 할인의 할인율(0~100 정수 퍼센트)
 * @property salesCountTimeToLive 구매가 이 기간 동안 상품의 최근 판매량에 들어간다
 * @property maxQuantityPerProduct 장바구니에 상품 하나를 담을 수 있는 최대 수량. 방 배경은 이와 관계없이 1개다
 * @property maxCartQuantity 장바구니 전체에 담을 수 있는 최대 수량
 */
@ConfigurationProperties("app.shop")
data class ShopProperties(
    val cdnBaseUrl: String = "https://cdn.nyummy.co.kr",
    val newUserTimeToLive: Duration = Duration.ofDays(7),
    val newProductTimeToLive: Duration = Duration.ofDays(7),
    val dailyDiscountRate: Int = 5,
    val salesCountTimeToLive: Duration = Duration.ofDays(30),
    val maxQuantityPerProduct: Int = 10,
    val maxCartQuantity: Int = 30,
)
