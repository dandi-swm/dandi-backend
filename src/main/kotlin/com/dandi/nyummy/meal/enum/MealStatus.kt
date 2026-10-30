package com.dandi.nyummy.meal.enum

enum class MealStatus {

    WAITING,
    ANALYZING,
    COMPLETED,
    FAILED,
    UNKNOWN,
    ;

    companion object {
        val ANALYZABLE_STATUSES = setOf(WAITING, FAILED)
        val IN_PROGRESS_STATUSES = setOf(WAITING, ANALYZING)
    }
}
