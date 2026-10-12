package com.dandi.nyummy.meal.controller

import com.dandi.nyummy.meal.dto.CreateAnalysisFeedbackRequest
import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.DailyMealsResponse
import com.dandi.nyummy.meal.dto.MealResponse
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.dto.MonthlyMealsResponse
import com.dandi.nyummy.meal.service.AnalysisFeedbackService
import com.dandi.nyummy.meal.service.AnalysisService
import com.dandi.nyummy.meal.service.MealService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
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

@Tag(name = "Meal", description = "식사 기록 조회 · 생성 · 수정 · 삭제 및 영양 분석 API")
@RestController
@RequestMapping("/api/v1/meals")
class MealController(
    private val mealService: MealService,
    private val analysisService: AnalysisService,
    private val analysisFeedbackService: AnalysisFeedbackService,
) {

    @Operation(
        summary = "월간 식사 캘린더 조회",
        description = "해당 연·월의 캘린더 범위(주 시작 일요일, 앞뒤 달 날짜 포함)에 대해 날짜별 하루 평가와 음식 아이콘 목록을 조회한다. " +
            "하루 평가는 칼로리·탄수화물·단백질·지방이 모두 권장 섭취량의 90% 이상 150% 미만이면 POSITIVE, " +
            "하나라도 벗어나면 NEGATIVE, 그날 식사 기록이 없으면 UNRECORDED다.",
    )
    @GetMapping("/monthly")
    fun getMonthlyMeals(
        @CurrentUser user: AuthUser,
        @Parameter(description = "조회 연도 (예: 2026)") @RequestParam year: Int,
        @Parameter(description = "조회 월 (1~12)") @RequestParam month: Int,
    ): MonthlyMealsResponse = mealService.getMonthlyMeals(user.userId, year, month)

    @Operation(
        summary = "일일 식사 목록 조회",
        description = "해당 날짜의 식사 목록과 하루 영양 합계(현재 섭취량·목표 섭취량)를 조회한다.",
    )
    @GetMapping("/daily")
    fun getDailyMeals(
        @CurrentUser user: AuthUser,
        @Parameter(description = "조회 연도 (예: 2026)") @RequestParam year: Int,
        @Parameter(description = "조회 월 (1~12)") @RequestParam month: Int,
        @Parameter(description = "조회 일 (1~31)") @RequestParam day: Int,
    ): DailyMealsResponse = mealService.getDailyMeals(user.userId, year, month, day)

    @Operation(summary = "식사 단건 조회", description = "식사 하나의 상세 정보(이름, 시각, 상태, 영양, 이미지 URL)를 조회한다. " +
        "영양 분석 평가 유무(hasAnalysisFeedback)가 들어 있어, 분석이 완료됐고(status=COMPLETED) " +
        "아직 평가하지 않은 식사에 별점 UI를 띄울 수 있다.",)
    @ApiErrorCodes(meal = [MealErrorCode.MEAL_NOT_FOUND], auth = [AuthErrorCode.FORBIDDEN])
    @GetMapping("/{mealId}")
    fun getMeal(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
    ): MealResponse = mealService.getMeal(user.userId, mealId)

    @Operation(summary = "식사 이름 수정", description = "식사의 이름을 수정하고 수정된 식사 정보를 반환한다.")
    @ApiErrorCodes(meal = [MealErrorCode.MEAL_NOT_FOUND], auth = [AuthErrorCode.FORBIDDEN])
    @PutMapping("/{mealId}")
    fun updateMeal(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
        @Parameter(description = "변경할 식사 이름") @RequestParam name: String,
    ): MealResponse = mealService.updateMeal(user.userId, mealId, name)

    @Operation(summary = "식사 삭제", description = "식사 기록을 삭제한다.")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiErrorCodes(meal = [MealErrorCode.MEAL_NOT_FOUND], auth = [AuthErrorCode.FORBIDDEN])
    @DeleteMapping("/{mealId}")
    fun deleteMeal(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
        response: HttpServletResponse,
    ) {
        mealService.deleteMeal(user.userId, mealId)
        response.status = HttpStatus.NO_CONTENT.value()
    }

    @Operation(
        summary = "식사 생성",
        description = "업로드된 이미지 키로 식사를 생성하고 영양 분석을 수행한 뒤, 식사 ID와 분석 상태를 반환한다. " +
            "영양 정보가 필요하면 식사 단건 조회 API를 사용한다.",
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
    fun createMeal(@CurrentUser user: AuthUser, @Valid @RequestBody request: CreateMealRequest): MealStatusResponse =
        mealService.createMeal(user.userId, request)

    @Operation(
        summary = "영양 분석 상태 조회",
        description = "식사의 영양 분석 상태를 조회한다. " +
            "UNKNOWN은 enum에 정의만 되어 있고 현재 서버가 내려주지 않는다. " +
            "영양 정보는 포함하지 않으며, COMPLETED 이후 식사 단건 조회 API로 가져온다.",
    )
    @ApiErrorCodes(meal = [MealErrorCode.MEAL_NOT_FOUND])
    @GetMapping("/{mealId}/analysis")
    fun getMealStatus(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable @NotNull @Valid mealId: Long,
    ): MealStatusResponse = analysisService.getMealStatus(user.userId, mealId)

    @Operation(summary = "영양 분석 재시도", description = "실패한 식사의 영양 분석을 다시 시도하고 변경된 분석 상태를 반환한다.")
    @ApiErrorCodes(meal = [MealErrorCode.MEAL_NOT_FOUND, MealErrorCode.ANALYSIS_NOT_RETRYABLE])
    @PostMapping("/{mealId}/analysis")
    fun retryNutritionAnalysis(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable @NotNull @Valid mealId: Long,
    ): MealStatusResponse = analysisService.retryNutritionAnalysis(user.userId, mealId)

    @Operation(
        summary = "영양 분석 평가 등록",
        description = "영양 분석 결과를 별점(0.5~5.0, 0.5 단위)과 이유로 평가한다. " +
            "분석이 완료된(COMPLETED) 식사만 평가할 수 있고, 식사당 한 번만 등록할 수 있다.",
    )
    @ApiResponse(responseCode = "201", description = "평가 등록 성공")
    @PostMapping("/{mealId}/analysis/feedback")
    fun createAnalysisFeedback(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable @NotNull @Valid mealId: Long,
        @Valid @RequestBody request: CreateAnalysisFeedbackRequest,
    ): ResponseEntity<Void> {
        analysisFeedbackService.createAnalysisFeedback(user.userId, mealId, request)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }
}
