package com.dandi.nyummy.meal.enum

enum class MealStatus {

    // v2 식사만 거친다. 식사 기록 요청 안에서 인식을 끝내지 못해 Worker의 인식 작업을 기다린다. 인식이 끝나면 CONFIRMING이 된다.
    RECOGNIZING,

    // v2 식사만 거친다. 인식이 끝나 음식 목록이 있고, 사용자가 목록을 확인해 보내기를 기다린다. 조금 뒤부터 가져갈 영양 작업이 있다.
    // 사용자가 목록을 보내면 WAITING이 되고, 보내지 않으면 그 작업을 Worker가 가져가 ANALYZING이 된다.
    CONFIRMING,
    WAITING,
    ANALYZING,
    COMPLETED,
    FAILED,
    UNKNOWN,
    ;

    companion object {
        val ANALYZABLE_STATUSES = setOf(WAITING, FAILED)
        val IN_PROGRESS_STATUSES = setOf(RECOGNIZING, CONFIRMING, WAITING, ANALYZING)

        // 음식 목록을 고쳐 보낼 수 있는 상태. 사용자의 확인을 기다리는 식사와 계산이 끝난 식사다.
        // 인식 중인 식사와 실패한 식사는 목록이 없고, 계산을 기다리거나 계산 중인 식사는 끝날 때까지 고칠 수 없다.
        val MEAL_FOOD_UPDATABLE_STATUSES = setOf(CONFIRMING, COMPLETED)
    }
}
