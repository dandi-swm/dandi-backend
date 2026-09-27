package com.dandi.nyummy.cat.calculator

import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.meal.calculator.calculateRecommendedDailyIntake
import com.dandi.nyummy.meal.enum.MealStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

fun isWeightUpdateDue(lastWeightUpdatedAt: Instant, today: LocalDate, zone: ZoneId, intervalDays: Int): Boolean {
    val lastUpdatedDate = lastWeightUpdatedAt.atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(lastUpdatedDate, today) >= intervalDays
}

fun calculateWeightStep(totalCalory: Int, targetCalory: Int, tolerance: Double): Int = when {
    totalCalory >= targetCalory * (1 + tolerance) -> 1
    totalCalory < targetCalory * (1 - tolerance) -> -1
    else -> 0
}
