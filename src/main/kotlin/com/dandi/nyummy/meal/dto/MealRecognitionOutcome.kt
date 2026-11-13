package com.dandi.nyummy.meal.dto

import com.dandi.nyummy.meal.enum.MealRecognitionDeferralCategory

/**
 * 식사 기록 요청 안에서 음식 인식을 시도한 결과. 무엇을 저장하고 어떻게 응답할지가 이 값으로 갈린다.
 */
sealed interface MealRecognitionOutcome {

    /** 응답 기한 안에 음식으로 인식했다. 음식 줄을 저장하고 영양 작업을 만든다. */
    data class Recognized(val recognition: MealRecognitionResult) : MealRecognitionOutcome

    /** 음식이 아니다. 식사만 남기고 작업은 만들지 않는다. */
    data object NotFood : MealRecognitionOutcome

    /** 요청 안에서 인식을 끝내지 못했다. Worker의 인식 작업으로 넘긴다. */
    data class Deferred(val category: MealRecognitionDeferralCategory) : MealRecognitionOutcome
}
