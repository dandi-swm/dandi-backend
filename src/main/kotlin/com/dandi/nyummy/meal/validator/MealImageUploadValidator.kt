package com.dandi.nyummy.meal.validator

import com.dandi.nyummy.image.enum.ImagePurpose
import com.dandi.nyummy.image.validator.ImageUploadValidator
import com.dandi.nyummy.meal.service.MealAttemptService
import org.springframework.stereotype.Component

/**
 * 식사 이미지 업로드 URL 발급 전에 하루 기록 시도 횟수를 검사한다.
 *
 * 업로드 전에 걸러낸다. 확정 단계에서만 막으면 사용자가 이미지를 다 올린 뒤에 거부당한다.
 */
@Component
class MealImageUploadValidator(private val mealAttemptService: MealAttemptService) : ImageUploadValidator {

    override val purpose = ImagePurpose.MEAL

    override fun validate(userId: Long) = mealAttemptService.validateDailyCount(userId)
}
