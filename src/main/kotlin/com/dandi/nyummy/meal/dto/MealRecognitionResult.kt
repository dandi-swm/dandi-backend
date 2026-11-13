package com.dandi.nyummy.meal.dto

import com.dandi.nyummy.meal.enum.MealInputCategory
import java.math.BigDecimal

/**
 * 사진에서 인식한 식사. 음식으로 인식한 사진만 이 결과가 된다(음식이 아니면 인식 클라이언트가 실패로 알린다).
 *
 * @property inputCategory 입력 유형. NOT_FOOD는 오지 않는다
 * @property name AI가 추천한 대표 이름
 * @property foods 음식 줄. 맨 앞이 대표 음식이고 한 줄 이상이다
 * @property model 인식에 쓴 모델 이름
 * @property promptVersion 인식에 쓴 프롬프트 버전
 */
data class MealRecognitionResult(
    val inputCategory: MealInputCategory,
    val name: String,
    val foods: List<MealFoodRecognitionResult>,
    val model: String,
    val promptVersion: String,
)

/**
 * 인식한 음식 한 줄.
 *
 * @property portion 인분
 */
data class MealFoodRecognitionResult(val name: String, val portion: BigDecimal)
