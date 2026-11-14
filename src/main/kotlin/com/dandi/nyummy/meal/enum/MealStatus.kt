package com.dandi.nyummy.meal.enum

enum class MealStatus {

    // v2 식사만 거친다. 식사 기록 요청 안에서 인식을 끝내지 못해 Worker의 인식 작업을 기다린다. 인식이 끝나면 WAITING이 된다.
    RECOGNIZING,
    WAITING,
    ANALYZING,
    COMPLETED,
    FAILED,
    UNKNOWN,
    ;

    companion object {
        val ANALYZABLE_STATUSES = setOf(WAITING, FAILED)
        val IN_PROGRESS_STATUSES = setOf(RECOGNIZING, WAITING, ANALYZING)
    }
}
