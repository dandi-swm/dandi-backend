package com.dandi.nyummy.shop.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.shop.enum.PromotionCategory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 프로모션 기간 [startsAt, endsAt)의 규칙을 다룬다. */
class PromotionTest {

    private val startsAt = Instant.parse("2026-11-01T00:00:00Z")
    private val endsAt = Instant.parse("2026-11-08T00:00:00Z")

    private fun promotion() = Promotion(
        name = "신입 집사 환영",
        category = PromotionCategory.NEW_USER,
        discountRate = 30,
        startsAt = startsAt,
        endsAt = endsAt,
    )

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    @Test
    fun `기간을 바꾸면 시작과 종료 시각이 함께 바뀐다`() {
        val promotion = promotion()
        val newEndsAt = endsAt.plusSeconds(60)

        promotion.updatePeriod(startsAt, newEndsAt)

        assertEquals(newEndsAt, promotion.endsAt)
    }

    @Test
    fun `종료 시각을 비우면 종료일이 없어진다`() {
        val promotion = promotion()

        promotion.updatePeriod(startsAt, null)

        assertNull(promotion.endsAt)
    }

    @Test
    fun `종료 시각이 시작 시각과 같거나 앞이면 거부하고 기간을 바꾸지 않는다`() {
        val promotion = promotion()

        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            promotion.updatePeriod(endsAt, endsAt)
        }
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            promotion.updatePeriod(endsAt, startsAt)
        }
        assertEquals(startsAt, promotion.startsAt)
        assertEquals(endsAt, promotion.endsAt)
    }
}
