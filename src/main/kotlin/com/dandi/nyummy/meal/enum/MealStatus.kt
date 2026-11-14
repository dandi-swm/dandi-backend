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

        // 음식 목록을 고쳐 보낼 수 있는 상태. 음식 목록이 있는 상태다. 인식 중인 식사와 실패한 식사는 고칠 수 없다.
        val MEAL_FOOD_UPDATABLE_STATUSES = setOf(WAITING, ANALYZING, COMPLETED)
    }
}
