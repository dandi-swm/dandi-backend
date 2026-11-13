package com.dandi.nyummy.meal.calculator

import com.dandi.nyummy.meal.config.MealRecognitionProperties
import java.time.Duration
import java.time.Instant

/**
 * 식사 기록 요청 안에서 음식 인식에 줄 수 있는 시간. 응답 기한에서 지금까지 쓴 시간과 저장·응답 몫을 뺀 값이다.
 *
 * 기한을 요청을 받은 때에 정해 두고 단계마다 남은 시간을 넘기므로, 앞 단계(검사·사진 확정)가 늦으면 인식이 받는 시간이 줄고
 * 빠르면 늘어난다. 모델에 고정된 시간을 주면 앞 단계가 늦을 때 전체가 기한을 넘는다.
 *
 * @param receivedAt 서버가 요청을 받은 시각
 * @param now 인식을 시작하려는 지금 시각
 * @return 인식에 줄 시간. [MealRecognitionProperties.minRecognitionTime]보다 적게 남았으면 불러도 대부분 시간 초과로
 *   끝나므로 부르지 않는다는 뜻으로 null
 */
fun calculateRecognitionTimeout(
    receivedAt: Instant,
    now: Instant,
    recognitionProperties: MealRecognitionProperties,
): Duration? {
    val elapsed = Duration.between(receivedAt, now)
    val timeout = recognitionProperties.responseTimeToLive - elapsed - recognitionProperties.reservedSaveTime

    return timeout.takeIf { it >= recognitionProperties.minRecognitionTime }
}
