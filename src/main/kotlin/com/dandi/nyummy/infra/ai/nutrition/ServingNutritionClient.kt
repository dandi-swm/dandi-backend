package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.meal.dto.PhotoServingNutritionInput
import com.dandi.nyummy.meal.dto.PhotoServingNutritionResult
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.enum.MealInputCategory

interface ServingNutritionClient {

    /**
     * 음식 이름만으로 음식마다 1인분 영양과 아이콘을 구한다. 사진을 보지 않으므로 그 이름의 일반적인 값이다.
     *
     * @param names 음식 이름들
     * @param inputCategory 식사의 입력 유형. 제품·영양성분표면 이름을 제품 이름으로 보고 그 제품의 영양 정보를 구한다
     * @return [names]와 같은 순서, 같은 개수의 결과
     * @throws AnalysisFailureException 실패를 [AnalysisFailureCategory]로 나눠 던진다
     */
    fun getNamedServingNutritions(names: List<String>, inputCategory: MealInputCategory): List<ServingNutritionResult>

    /**
     * 음식 사진을 함께 보내 음식마다 1인분 영양, 인분, 아이콘을 구한다. 이름만으로 구하는 [getNamedServingNutritions]와 달리
     * 사진 속 그 음식의 재료와 양을 보고 구한다.
     *
     * 사진에 식사 전체의 값이 적혀 있으면 그 값도 읽어 돌려준다. 음식별 값을 그 합계에 맞추는 것은 부르는 쪽이 한다.
     *
     * @param imageBytes 사진 바이트
     * @param contentType 사진의 MIME 타입
     * @param foods 영양을 구할 음식들. 사진에 일부 값이 적혀 있던 음식은 그 값을 함께 넘긴다
     * @return [foods]와 같은 순서, 같은 개수의 결과와 사진에 적힌 식사 전체의 값
     * @throws AnalysisFailureException 실패를 [AnalysisFailureCategory]로 나눠 던진다
     */
    fun getPhotoServingNutritions(
        imageBytes: ByteArray,
        contentType: String,
        foods: List<PhotoServingNutritionInput>,
    ): PhotoServingNutritionResult
}
