package com.dandi.nyummy.meal.controller

import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.language.CurrentLanguage
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.CreateAnalysisFeedbackRequest
import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.CreateMealV2Response
import com.dandi.nyummy.meal.dto.DailyMealsV2Response
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.dto.MealV2Response
import com.dandi.nyummy.meal.dto.MonthlyMealsResponse
import com.dandi.nyummy.meal.dto.UpdateMealFoodsRequest
import com.dandi.nyummy.meal.dto.UpdateMealNameRequest
import com.dandi.nyummy.meal.service.AnalysisFeedbackService
import com.dandi.nyummy.meal.service.MealService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Meal v2", description = "AI 고도화 영양 분석 식사 API. 기록·상태·단건·하루 목록·음식 목록 보내기·이름 수정·기록 취소는 지금 고정 응답(Stub)이다")
@RestController
@RequestMapping("/api/v2/meals")
class MealV2Controller(
    private val mealService: MealService,
    private val analysisFeedbackService: AnalysisFeedbackService,
) {

    @Operation(
        summary = "식사 생성 v2",
        description = "업로드된 이미지 키로 식사를 생성하고, 응답 전에 사진의 음식을 인식한다. 서버는 요청을 받고 4.5초 안에 응답한다. " +
            "음식으로 인식하면 status는 WAITING이고 대표 이름(20자 이내)과 음식 줄을 준다. 맨 위 줄이 대표 음식이고, " +
            "음식 줄의 nutrition과 iconId는 영양 계산 전이라 null이다. 영양은 5분 뒤에, 또는 음식 목록을 보내면 바로 계산한다. " +
            "음식이 아니면 status는 FAILED이고 name은 빈 문자열, foods는 null이다. " +
            "기한 안에 인식을 끝내지 못하면 status는 RECOGNIZING이고 name과 foods는 null이다. 서버가 이어서 인식하므로 " +
            "영양 분석 상태 조회 API로 WAITING이 되는지 확인한다. " +
            "어느 경우든 기록 횟수에 들어가고 보상 상자 우편이 하나 생기며, 응답의 rewardBoxCategory에 그 상자의 등급을 담는다.",
    )
    @ApiErrorCodes(
        meal = [MealErrorCode.DUPLICATE_IMAGE_KEY, MealErrorCode.DAILY_COUNT_EXCEEDED, MealErrorCode.STALE_IMAGE],
        s3 = [
            S3ErrorCode.INVALID_KEY,
            S3ErrorCode.OBJECT_NOT_FOUND,
            S3ErrorCode.FILE_SIZE_EXCEEDED,
            S3ErrorCode.UNSUPPORTED_CONTENT_TYPE,
        ],
    )
    @PostMapping
    fun createMealV2(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: CreateMealRequest,
        @CurrentLanguage language: Language,
    ): CreateMealV2Response = mealService.createMealV2(user.userId, request, language)

    @Operation(
        summary = "영양 분석 상태 조회 v2",
        description = "대기 화면과 계산 중 화면에서 폴링한다. 5초 안에 인식하지 못한 식사는 RECOGNIZING으로 시작해 " +
            "인식이 끝나면 WAITING(음식 목록 있음)이 되고, 영양 계산이 끝나면 COMPLETED가 된다.",
    )
    @ApiErrorCodes(meal = [MealErrorCode.MEAL_NOT_FOUND], auth = [AuthErrorCode.FORBIDDEN])
    @GetMapping("/{mealId}/analysis")
    fun getMealStatusV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
    ): MealStatusResponse = mealService.getMealStatus(user.userId, mealId)

    @Operation(
        summary = "식사 단건 조회 v2",
        description = "v1 응답에 analysisVersion(V1·V2)과 음식 줄(foods)을 더했다. " +
            "V1 식사, 아직 인식 중인 식사, 음식이 아닌 식사는 foods가 null이다. " +
            "음식 줄의 영양과 iconId는 계산 전이면 null이고, 맨 위 줄(대표 음식)의 아이콘이 식사 iconId다. " +
            "고양이 한마디(catComment)는 처음 계산이 끝날 때 한 번만 만들고 바뀌지 않는다.",
    )
    @ApiErrorCodes(meal = [MealErrorCode.MEAL_NOT_FOUND], auth = [AuthErrorCode.FORBIDDEN])
    @GetMapping("/{mealId}")
    fun getMealV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
    ): MealV2Response = mealService.getMealV2(user.userId, mealId)

    @Operation(
        summary = "하루 식사 목록 조회 v2",
        description = "v1 응답의 식사마다 analysisVersion(V1·V2)을 더했다. 앱은 이 값으로 상세 화면을 고른다. " +
            "영양 계산이 끝나지 않은 식사는 영양이 0이다.",
    )
    @GetMapping("/daily")
    fun getDailyMealsV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "조회 연도 (예: 2026)") @RequestParam year: Int,
        @Parameter(description = "조회 월 (1~12)") @RequestParam month: Int,
        @Parameter(description = "조회 일 (1~31)") @RequestParam day: Int,
    ): DailyMealsV2Response = mealService.getDailyMealsV2(user.userId, year, month, day)

    @Operation(
        summary = "음식 목록 보내기 v2",
        description = "목록 화면의 버튼 하나로 음식 목록 전체(1~10줄)를 보낸다. 고치지 않았어도 같은 목록을 보낸다. " +
            "맨 위 줄이 대표 음식이다. 새로 추가한 줄은 mealFoodId를 null로, 요청에 없는 기존 줄은 삭제된다. " +
            "이름은 1~30자, 인분은 0.5~5.0, 0.5 단위다. " +
            "nutrition(칼로리 0~9999, 탄수화물·단백질·지방 0~999)과 iconId는 이번에 직접 고친 줄만 채운다. " +
            "채운 줄은 그 값으로 고정돼 이름을 바꿔도 다시 구하지 않는다. " +
            "기다리던 계산은 버리고 받은 목록으로 다시 계산한다. 응답의 status가 WAITING이면 분석 상태를 폴링하고, " +
            "COMPLETED면 이미 계산이 끝난 것이므로 바로 단건을 다시 조회한다. " +
            "COMPLETED는 값을 새로 구할 것이 없을 때다(인분·순서만 바꿈, 줄을 지움, 영양·아이콘을 직접 적음. " +
            "한 번 계산이 끝난 식사만). " +
            "아직 인식 중(RECOGNIZING)이거나 실패(FAILED)한 식사, V1 식사는 받지 않는다.",
    )
    @ApiErrorCodes(
        meal = [
            MealErrorCode.MEAL_NOT_FOUND,
            MealErrorCode.MEAL_FOODS_NOT_UPDATABLE,
            MealErrorCode.MEAL_FOOD_NOT_FOUND,
        ],
        auth = [AuthErrorCode.FORBIDDEN],
    )
    @PutMapping("/{mealId}/foods")
    fun updateMealFoodsV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
        @Valid @RequestBody request: UpdateMealFoodsRequest,
        @CurrentLanguage language: Language,
    ): MealStatusResponse = mealService.updateMealFoodsV2(user.userId, mealId, request, language)

    @Operation(
        summary = "식사 이름 수정 v2",
        description = "대표 이름을 본문으로 받는다(1~20자). 음식 줄과 영양은 바뀌지 않고 다시 계산하지 않는다. " +
            "응답은 단건 조회와 같은 모양이다.",
    )
    @ApiErrorCodes(meal = [MealErrorCode.MEAL_NOT_FOUND], auth = [AuthErrorCode.FORBIDDEN])
    @PutMapping("/{mealId}")
    fun updateMealNameV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
        @Valid @RequestBody request: UpdateMealNameRequest,
    ): MealV2Response = mealService.updateMealNameV2(user.userId, mealId, request)

    @Operation(
        summary = "식사 기록 취소 v2 (Stub)",
        description = "기록 직후 음식 목록 화면의 X 버튼이다. 그 밖의 X는 삭제를 부른다. 지금은 아무것도 하지 않고 204를 준다.",
    )
    @ApiResponse(responseCode = "204", description = "취소 성공")
    @PostMapping("/{mealId}/cancel")
    fun cancelMealV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
    ): ResponseEntity<Void> {
        mealService.cancelMealV2()
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "월간 식사 캘린더 조회 v2", description = "v1과 같다.")
    @GetMapping("/monthly")
    fun getMonthlyMealsV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "조회 연도 (예: 2026)") @RequestParam year: Int,
        @Parameter(description = "조회 월 (1~12)") @RequestParam month: Int,
    ): MonthlyMealsResponse = mealService.getMonthlyMeals(user.userId, year, month)

    @Operation(summary = "식사 삭제 v2", description = "v1과 같다.")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiErrorCodes(meal = [MealErrorCode.MEAL_NOT_FOUND], auth = [AuthErrorCode.FORBIDDEN])
    @DeleteMapping("/{mealId}")
    fun deleteMealV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
    ): ResponseEntity<Void> {
        mealService.deleteMeal(user.userId, mealId)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "튜토리얼 식사 생성 v2", description = "v1과 같다.")
    @ApiResponse(responseCode = "204", description = "생성 성공")
    @ApiErrorCodes(meal = [MealErrorCode.TUTORIAL_ALREADY_COMPLETED])
    @PostMapping("/tutorial")
    fun createTutorialMealV2(@CurrentUser user: AuthUser, @CurrentLanguage language: Language): ResponseEntity<Void> {
        mealService.createTutorialMeal(user.userId, language)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "영양 분석 재시도 v2", description = "v1과 같다.")
    @ApiErrorCodes(
        meal = [MealErrorCode.MEAL_NOT_FOUND, MealErrorCode.ANALYSIS_NOT_RETRYABLE],
        auth = [AuthErrorCode.FORBIDDEN],
    )
    @PostMapping("/{mealId}/analysis")
    fun retryNutritionAnalysisV2(
        @CurrentUser user: AuthUser,
        @CurrentLanguage language: Language,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
    ): MealStatusResponse = mealService.retryNutritionAnalysis(user.userId, mealId, language)

    @Operation(summary = "영양 분석 평가 등록 v2", description = "v1과 같다.")
    @ApiResponse(responseCode = "201", description = "평가 등록 성공")
    @PostMapping("/{mealId}/analysis/feedback")
    fun createAnalysisFeedbackV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
        @Valid @RequestBody request: CreateAnalysisFeedbackRequest,
    ): ResponseEntity<Void> {
        analysisFeedbackService.createAnalysisFeedback(user.userId, mealId, request)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }
}
