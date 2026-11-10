package com.dandi.nyummy.report.calculator

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportPurchaseCalculatorTest {

    // 2026-11-01은 일요일이다.
    private val sunday = LocalDate.parse("2026-11-01")

    // isPurchasableWeek

    @Test
    fun `일요일에 시작한 주는 다음 일요일부터 살 수 있다`() {
        assertTrue(isPurchasableWeek(sunday, today = sunday.plusDays(7)))
        assertTrue(isPurchasableWeek(sunday, today = sunday.plusDays(30)))
    }

    @Test
    fun `토요일까지는 아직 끝나지 않은 주라 살 수 없다`() {
        assertFalse(isPurchasableWeek(sunday, today = sunday.plusDays(6)))
        assertFalse(isPurchasableWeek(sunday, today = sunday))
    }

    @Test
    fun `일요일이 아닌 날로 시작하면 살 수 없다`() {
        assertFalse(isPurchasableWeek(sunday.plusDays(1), today = sunday.plusDays(30)))
        assertFalse(isPurchasableWeek(sunday.minusDays(1), today = sunday.plusDays(30)))
    }

    // calculateReportPurchasePrice

    @Test
    fun `무료로 산 리포트가 남아 있지 않으면 첫 무료라 0이다`() {
        assertEquals(0, calculateReportPurchasePrice(hasFreeReportPurchase = false, listPrice = 800))
    }

    @Test
    fun `무료로 산 리포트가 남아 있으면 정가다`() {
        assertEquals(800, calculateReportPurchasePrice(hasFreeReportPurchase = true, listPrice = 800))
    }
}
