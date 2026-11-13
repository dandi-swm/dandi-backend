package com.dandi.nyummy.meal.enum

/**
 * 식사를 어디서 인식했는지. 스냅샷에 남겨 인식한 곳별로 결과를 나눠 본다.
 */
enum class MealRecognitionCategory {

    // 식사 기록 요청 안에서 인식했다.
    SYNC,

    // Worker가 인식 작업으로 인식했다.
    WORKER,
}
