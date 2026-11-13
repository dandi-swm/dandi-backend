package com.dandi.nyummy.meal.config

import java.time.Duration

/**
 * 식사 인식 v2의 식사 기록(POST /api/v2/meals) 설정. app.meal.recognition에 둔다.
 *
 * @property responseTimeToLive 서버가 요청을 받은 때부터 응답까지의 기한
 * @property reservedSaveTime 인식 뒤의 저장과 응답에 남겨 두는 시간
 * @property minRecognitionTime 인식에 줄 수 있는 시간이 이보다 적으면 모델을 부르지 않는다
 * @property maxThreads 식사 기록 요청 안에서 동시에 도는 인식 수
 * @property nutritionDelay 인식한 뒤 Worker가 영양 작업을 가져가기까지 기다리는 시간
 */
data class MealRecognitionProperties(
    val responseTimeToLive: Duration,
    val reservedSaveTime: Duration,
    val minRecognitionTime: Duration,
    val maxThreads: Int,
    val nutritionDelay: Duration,
) {
    // 설정이 틀린 채로 모든 기록이 조용히 Worker로 넘어가지 않도록 앱이 뜰 때 막는다.
    init {
        require(responseTimeToLive >= reservedSaveTime + minRecognitionTime) {
            "app.meal.recognition의 response-time-to-live($responseTimeToLive)가 " +
                "reserved-save-time($reservedSaveTime) + min-recognition-time($minRecognitionTime)보다 짧아 인식을 부를 수 없습니다."
        }
        require(maxThreads > 0) { "app.meal.recognition.max-threads는 1 이상이어야 합니다: $maxThreads" }
        require(!nutritionDelay.isNegative) { "app.meal.recognition.nutrition-delay는 음수일 수 없습니다: $nutritionDelay" }
    }
}
