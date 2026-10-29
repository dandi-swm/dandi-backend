package com.dandi.nyummy.shop.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 배너 노출 기간 [startsAt, endsAt)의 규칙을 다룬다. */
class ShopBannerTest {

    private val startsAt = Instant.parse("2026-11-01T00:00:00Z")
    private val endsAt = Instant.parse("2026-11-15T00:00:00Z")

    private fun shopBanner() = ShopBanner(
        promotionId = 3,
        imageKey = "shop-banners/old.png",
        title = "첫 구매 혜택",
        titleColor = "contentOnInverse",
        displayOrder = 0,
        startsAt = startsAt,
        endsAt = endsAt,
        adminId = 1,
    )

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    @Test
    fun `노출 기간을 바꾸면 시작과 종료 시각이 함께 바뀐다`() {
        val shopBanner = shopBanner()
        val newStartsAt = startsAt.plusSeconds(60)
        val newEndsAt = endsAt.plusSeconds(60)

        shopBanner.updatePeriod(newStartsAt, newEndsAt)

        assertEquals(newStartsAt, shopBanner.startsAt)
        assertEquals(newEndsAt, shopBanner.endsAt)
    }

    @Test
    fun `종료 시각을 비우면 내릴 때까지 노출한다`() {
        val shopBanner = shopBanner()

        shopBanner.updatePeriod(startsAt, null)

        assertNull(shopBanner.endsAt)
    }

    @Test
    fun `종료 시각이 시작 시각과 같거나 앞이면 거부하고 기간을 바꾸지 않는다`() {
        val shopBanner = shopBanner()

        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            shopBanner.updatePeriod(endsAt, endsAt)
        }
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            shopBanner.updatePeriod(endsAt, startsAt)
        }
        assertEquals(startsAt, shopBanner.startsAt)
        assertEquals(endsAt, shopBanner.endsAt)
    }
}
