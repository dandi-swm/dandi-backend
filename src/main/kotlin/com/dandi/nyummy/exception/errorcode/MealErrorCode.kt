package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class MealErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    MEAL_NOT_FOUND(HttpStatus.NOT_FOUND, "api.meal.notFound", "요청한 mealId가 데이터베이스에 존재하지 않습니다."),
    ANALYSIS_NOT_RETRYABLE(HttpStatus.CONFLICT, "api.meal.analysisNotRetryable", "FAILED 상태의 식사만 재시도할 수 있습니다."),
    DUPLICATE_IMAGE_KEY(HttpStatus.CONFLICT, "api.meal.duplicateImageKey", "이미 등록된 imageKey입니다."),
    STALE_IMAGE(HttpStatus.BAD_REQUEST, "api.meal.staleImage", "오늘 촬영한 사진만 등록할 수 있습니다."),
    DAILY_COUNT_EXCEEDED(HttpStatus.CONFLICT, "api.meal.dailyCountExceeded", "하루에 등록할 수 있는 식사 개수를 초과했습니다."),
    INVALID_RATING(HttpStatus.BAD_REQUEST, "api.meal.invalidRating", "별점은 0.5 ~ 5.0 사이의 0.5 단위 값이어야 합니다."),
    ANALYSIS_FEEDBACK_ALREADY_EXISTS(HttpStatus.CONFLICT, "api.meal.analysisFeedbackAlreadyExists", "이미 평가한 식사입니다."),
    ANALYSIS_NOT_COMPLETED(HttpStatus.CONFLICT, "api.meal.analysisNotCompleted", "분석이 완료된 식사만 평가·수정할 수 있습니다."),
    TUTORIAL_ALREADY_COMPLETED(HttpStatus.CONFLICT, "api.meal.tutorialAlreadyCompleted", "이미 튜토리얼을 완료했습니다."),
    MEAL_FOODS_NOT_UPDATABLE(
        HttpStatus.CONFLICT,
        "api.meal.mealFoodsNotUpdatable",
        "음식 목록을 고칠 수 없는 식사입니다. 아직 인식 중이거나 계산 중인 식사, 실패한 식사, 음식 목록이 없는 식사입니다.",
    ),
    MEAL_FOOD_NOT_FOUND(HttpStatus.BAD_REQUEST, "api.meal.mealFoodNotFound", "이 식사의 음식 줄이 아닌 mealFoodId입니다."),
}
