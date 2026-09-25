package com.dandi.nyummy.meal.calculator

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarCalculatorTest {

    @Test
    fun `1일이 목요일이면 이전 일요일까지 앞으로 확장한다`() {
        val (startDate, endDate) = calculateMonthlyCalendarRange(YearMonth.of(2026, 1))

        assertEquals(LocalDate.of(2025, 12, 28), startDate)
        assertEquals(LocalDate.of(2026, 1, 31), endDate)
    }

    @Test
    fun `1일이 일요일이고 말일이 토요일이면 확장하지 않는다`() {
        val (startDate, endDate) = calculateMonthlyCalendarRange(YearMonth.of(2026, 2))

        assertEquals(LocalDate.of(2026, 2, 1), startDate)
        assertEquals(LocalDate.of(2026, 2, 28), endDate)
    }

    @Test
    fun `말일이 화요일이면 다음 토요일까지 뒤로 확장한다`() {
        val (startDate, endDate) = calculateMonthlyCalendarRange(YearMonth.of(2026, 3))

        assertEquals(LocalDate.of(2026, 3, 1), startDate)
        assertEquals(LocalDate.of(2026, 4, 4), endDate)
    }

    @Test
    fun `윤년 2월도 범위를 계산한다`() {
        val (startDate, endDate) = calculateMonthlyCalendarRange(YearMonth.of(2024, 2))

        assertEquals(LocalDate.of(2024, 1, 28), startDate)
        assertEquals(LocalDate.of(2024, 3, 2), endDate)
    }

    @Test
    fun `12월은 다음 해로 넘어가 확장한다`() {
        val (startDate, endDate) = calculateMonthlyCalendarRange(YearMonth.of(2025, 12))

        assertEquals(LocalDate.of(2025, 11, 30), startDate)
        assertEquals(LocalDate.of(2026, 1, 3), endDate)
    }

    @Test
    fun `범위는 항상 일요일에 시작해 토요일에 끝나고 일수는 7의 배수다`() {
        val months = (1..12).map { YearMonth.of(2026, it) } + YearMonth.of(2024, 2)

        months.forEach { yearMonth ->
            val (startDate, endDate) = calculateMonthlyCalendarRange(yearMonth)
            val days = ChronoUnit.DAYS.between(startDate, endDate) + 1

            assertEquals(DayOfWeek.SUNDAY, startDate.dayOfWeek, "$yearMonth 시작일")
            assertEquals(DayOfWeek.SATURDAY, endDate.dayOfWeek, "$yearMonth 종료일")
            assertEquals(0L, days % 7, "$yearMonth 일수=$days")
        }
    }
}
