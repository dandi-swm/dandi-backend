package com.dandi.nyummy.infra.ai.recognition

import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import java.time.Duration

interface MealRecognitionClient {

    /**
     * 사진에서 입력 유형, 대표 이름, 음식 줄을 인식한다.
     *
     * @param imageBytes 사진 바이트
     * @param contentType 사진의 MIME 타입
     * @param language 대표 이름과 음식 이름을 쓸 언어. 식사를 기록한 요청의 앱 언어다
     * @param timeout 응답을 기다릴 최대 시간. 식사 기록 요청 안에서 부를 때 남은 시간을 넘긴다. null이면 기본 제한 시간을 쓴다
     * @throws AnalysisFailureException 음식이 아니면 [AnalysisFailureCategory.NOT_FOOD], 그 밖의 실패는 이유에 맞는 분류
     */
    fun recognizeMeal(
        imageBytes: ByteArray,
        contentType: String,
        language: Language,
        timeout: Duration? = null,
    ): MealRecognitionResult
}
