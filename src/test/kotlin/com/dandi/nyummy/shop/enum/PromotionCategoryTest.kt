package com.dandi.nyummy.shop.enum

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromotionCategoryTest {

    private val userCreatedAt = Instant.parse("2026-11-01T00:00:00Z")
    private val newUserTimeToLive = Duration.ofDays(7)

    private fun PromotionCategory.isEligibleAt(now: Instant, hasPurchased: Boolean = false) =
        isEligible(userCreatedAt, hasPurchased, now, newUserTimeToLive)

    @Test
    fun `신입 집사는 가입 후 7일 동안만 대상이다`() {
        val sevenDaysLater = userCreatedAt.plus(newUserTimeToLive)

        assertTrue(PromotionCategory.NEW_USER.isEligibleAt(userCreatedAt))
        assertTrue(PromotionCategory.NEW_USER.isEligibleAt(sevenDaysLater.minusSeconds(1)))
        assertFalse(PromotionCategory.NEW_USER.isEligibleAt(sevenDaysLater))
    }

    @Test
    fun `모든 사용자·오늘의 할인은 샀든 안 샀든 언제나 대상이다`() {
        val longAfter = userCreatedAt.plus(Duration.ofDays(365))

        assertTrue(PromotionCategory.GENERAL.isEligibleAt(longAfter, hasPurchased = true))
        assertTrue(PromotionCategory.DAILY.isEligibleAt(longAfter, hasPurchased = true))
    }

    @Test
    fun `첫 구매 혜택은 한 번도 사지 않은 사용자만 대상이다`() {
        val longAfter = userCreatedAt.plus(Duration.ofDays(365))

        assertTrue(PromotionCategory.FIRST_PURCHASE.isEligibleAt(longAfter, hasPurchased = false))
        assertFalse(PromotionCategory.FIRST_PURCHASE.isEligibleAt(longAfter, hasPurchased = true))
    }

    @Test
    fun `복귀 집사는 아직 아무도 대상이 아니다`() {
        assertFalse(PromotionCategory.RETURNING_USER.isEligibleAt(userCreatedAt))
    }
}
