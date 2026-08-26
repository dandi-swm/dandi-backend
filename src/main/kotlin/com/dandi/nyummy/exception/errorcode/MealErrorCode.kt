package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class MealErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    MEAL_NOT_FOUND(HttpStatus.NOT_FOUND, "api.meal.notFound", "요청한 mealId가 데이터베이스에 존재하지 않습니다."),
    ANALYSIS_NOT_RETRYABLE(HttpStatus.CONFLICT, "api.meal.analysisNotRetryable", "FAILED 상태의 식사만 재시도할 수 있습니다."),
    DUPLICATE_IMAGE_KEY(HttpStatus.CONFLICT, "api.meal.duplicateImageKey", "이미 등록된 imageKey입니다."),
}
