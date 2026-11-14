package com.dandi.nyummy.meal.enum

/**
 * 식사 기록 요청 안에서 음식 인식을 끝내지 못해 Worker의 인식 작업으로 넘긴 이유.
 */
enum class MealRecognitionDeferralCategory {

    // 앞 단계(검사·사진 확정)가 늦어 인식에 줄 시간이 인식 최소 시간보다 적었다. 모델을 부르지 않았다.
    INSUFFICIENT_TIME,

    // 응답 기한까지 인식이 끝나지 않았다.
    TIMEOUT,

    // 인식 실행기가 모두 바빠 제출이 거부됐다.
    SATURATED,

    // 인식이 실패했다. 요청 한도 초과·서버 오류처럼 다시 하면 될 수 있는 실패와 그렇지 않은 실패를 가리지 않는다.
    FAILED,
}
