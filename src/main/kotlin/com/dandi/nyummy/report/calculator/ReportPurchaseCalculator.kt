package com.dandi.nyummy.report.calculator

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 리포트를 살 수 있는 주인지. 일요일에 시작하고, 이미 끝난 주(토요일이 [today] 이전)여야 한다.
 * 이번 주는 다음 일요일부터 살 수 있다. 날짜는 모두 Asia/Seoul 날짜다.
 */
fun isPurchasableWeek(startDate: LocalDate, today: LocalDate): Boolean =
    startDate.dayOfWeek == DayOfWeek.SUNDAY && !startDate.plusWeeks(1).isAfter(today)

/**
 * 지금 구매하면 낼 코인. 무료로 산 리포트가 취소·실패 없이 남아 있으면 [listPrice]이고, 없으면 첫 무료(0)다.
 * 무료로 산 리포트가 취소되거나 생성에 실패하면 무료 기회가 다시 생긴다.
 */
fun calculateReportPurchasePrice(hasFreeReportPurchase: Boolean, listPrice: Int): Int =
    if (hasFreeReportPurchase) listPrice else 0
