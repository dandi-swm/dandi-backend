package com.dandi.nyummy.shop.enum

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromotionCategoryTest {

    private val userCreatedAt = Instant.parse("2026-11-01T00:00:00Z")
    private val newUserTimeToLive = Duration.ofDays(7)

    private fun PromotionCategory.isEligibleAt(now: Instant) = isEligible(userCreatedAt, now, newUserTimeToLive)

    @Test
    fun `신입 집사는 가입 후 7일 동안만 대상이다`() {
        val sevenDaysLater = userCreatedAt.plus(newUserTimeToLive)

        assertTrue(PromotionCategory.NEW_USER.isEligibleAt(userCreatedAt))
        assertTrue(PromotionCategory.NEW_USER.isEligibleAt(sevenDaysLater.minusSeconds(1)))
        assertFalse(PromotionCategory.NEW_USER.isEligibleAt(sevenDaysLater))
    }

    @Test
    fun `모든 사용자·오늘의 할인·첫 구매는 언제나 대상이다`() {
        val longAfter = userCreatedAt.plus(Duration.ofDays(365))

        assertTrue(PromotionCategory.GENERAL.isEligibleAt(longAfter))
        assertTrue(PromotionCategory.DAILY.isEligibleAt(longAfter))
        assertTrue(PromotionCategory.FIRST_PURCHASE.isEligibleAt(longAfter))
    }

    @Test
    fun `복귀 집사는 아직 아무도 대상이 아니다`() {
        assertFalse(PromotionCategory.RETURNING_USER.isEligibleAt(userCreatedAt))
    }
}
