package com.dandi.nyummy.meal.dto

import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.NetContentUnit
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
) {

    /**
     * 대표 이름과 음식 이름이 [language]의 글자로 쓰였는지. 모델이 요청한 언어가 아닌 언어를 섞어 썼는지 가려낸다.
     * 포장에 적힌 제품 이름은 다른 언어여도 적힌 그대로 옮기는 것이 맞으므로 보지 않는다.
     */
    fun matchesLanguage(language: Language): Boolean =
        language.matchesScript(name) && foods.filterNot { it.hasPackageName }.all { language.matchesScript(it.name) }
}

/**
 * 인식한 음식 한 줄. 인분은 인식이 정하지 않는다.
 *
 * @property nutritionHint 사진에 이 음식의 값으로 적혀 있던 영양. 적힌 것이 없으면 null
 * @property netContent 사진에 적혀 있던 제품의 총 내용량. 제품이 아니거나 적힌 것이 없으면 null
 * @property netContentUnit 총 내용량의 단위. [netContent]와 함께 있거나 함께 없다
 * @property hasPackageName 이름이 포장에 적힌 제품명인지. 제품이 아니거나, 제품명이 사진에 보이지 않아 문구나 종류를 적었으면 false
 */
data class MealFoodRecognitionResult(
    val name: String,
    val nutritionHint: NutritionHint? = null,
    val netContent: BigDecimal? = null,
    val netContentUnit: NetContentUnit? = null,
    val hasPackageName: Boolean = false,
)

/**
 * 사진에 적혀 있어 인식이 읽은 음식 하나의 영양 값(힌트). 음식 옆에 직접 적은 숫자, 메뉴판의 숫자 같은 것이다.
 * 모델이 짐작한 값이 아니라 적힌 값이라, 있으면 그 줄의 1인분 영양으로 그대로 쓴다.
 * 일부만 적혀 있을 수 있어 항목마다 null일 수 있고, 넷 중 하나는 값이 있다.
 */
data class NutritionHint(
    val calory: BigDecimal? = null,
    val carbs: BigDecimal? = null,
    val protein: BigDecimal? = null,
    val fat: BigDecimal? = null,
)
