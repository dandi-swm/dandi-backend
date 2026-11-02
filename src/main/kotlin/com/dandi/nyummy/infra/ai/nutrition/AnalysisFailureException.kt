package com.dandi.nyummy.infra.ai.nutrition

/**
 * [NutritionAnalysisClient] 구현체가 분석에 실패했을 때 던지는 예외.
 * 제공자마다 다른 오류(HTTP 상태·SDK 예외·응답 형식)를 [category]로 번역해, 호출하는 쪽이 제공자를 몰라도 되게 한다.
 */
class AnalysisFailureException(val category: AnalysisFailureCategory, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
