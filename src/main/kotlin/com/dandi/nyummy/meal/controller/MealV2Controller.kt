package com.dandi.nyummy.meal.controller

import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
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
        summary = "식사 생성 v2 (Stub)",
        description = "AI 고도화 영양 분석의 식사 생성이다. 지금은 고정 응답이고 실제 인식·저장을 하지 않는다. " +
            "5초 안에 인식된 경우의 모양으로 대표 이름(20자 이내)과 음식 줄을 준다. 맨 위 줄이 대표 음식이다. " +
            "음식 줄의 nutrition과 iconId는 영양 계산 전이라 null이다.",
    )
    @PostMapping
    fun createMealV2(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: CreateMealRequest,
    ): CreateMealV2Response = mealService.createMealV2()

    @Operation(
        summary = "영양 분석 상태 조회 v2 (Stub)",
        description = "지금은 고정 응답으로 COMPLETED를 준다. 실제 구현에서는 5초를 넘긴 식사가 RECOGNIZING으로 시작한다.",
    )
    @GetMapping("/{mealId}/analysis")
    fun getMealStatusV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
    ): MealStatusResponse = mealService.getMealStatusV2()

    @Operation(
        summary = "식사 단건 조회 v2 (Stub)",
        description = "지금은 고정 응답이다. v1 응답에 analysisVersion(V1·V2)과 음식 줄(foods)을 더했다. " +
            "V1 식사는 foods가 null이다. 음식 줄마다 iconId가 있고, 맨 위 줄(대표 음식)의 아이콘이 식사 iconId다. " +
            "고양이 한마디(catComment)는 처음 계산이 끝날 때 한 번만 만들고 바뀌지 않는다.",
    )
    @GetMapping("/{mealId}")
    fun getMealV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
    ): MealV2Response = mealService.getMealV2()

    @Operation(
        summary = "하루 식사 목록 조회 v2 (Stub)",
        description = "지금은 날짜와 상관없이 같은 고정 응답이다. v1 응답의 식사마다 analysisVersion(V1·V2)을 더했다. " +
            "앱은 이 값으로 상세 화면을 고른다.",
    )
    @GetMapping("/daily")
    fun getDailyMealsV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "조회 연도 (예: 2026)") @RequestParam year: Int,
        @Parameter(description = "조회 월 (1~12)") @RequestParam month: Int,
        @Parameter(description = "조회 일 (1~31)") @RequestParam day: Int,
    ): DailyMealsV2Response = mealService.getDailyMealsV2()

    @Operation(
        summary = "음식 목록 보내기 v2 (Stub)",
        description = "목록 화면의 버튼 하나로 음식 목록 전체(1~10줄)를 보낸다. 고치지 않았어도 같은 목록을 보낸다. " +
            "맨 위 줄이 대표 음식이다. 새로 추가한 줄은 mealFoodId를 null로, 요청에 없는 기존 줄은 삭제된다. " +
            "이름은 1~30자, 인분은 0.5~5.0, 0.5 단위다. " +
            "nutrition(칼로리 0~9999, 탄수화물·단백질·지방 0~999)과 iconId는 이번에 직접 고친 줄만 채운다. " +
            "지금은 고정 응답으로 WAITING을 주고, 없는 아이콘 id는 가려내지 않는다.",
    )
    @PutMapping("/{mealId}/foods")
    fun updateMealFoodsV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
        @Valid @RequestBody request: UpdateMealFoodsRequest,
    ): MealStatusResponse = mealService.updateMealFoodsV2(request)

    @Operation(
        summary = "식사 이름 수정 v2 (Stub)",
        description = "대표 이름을 본문으로 받는다(1~20자). 지금은 고정 응답으로 받은 이름을 넣은 단건 모양을 준다.",
    )
    @PutMapping("/{mealId}")
    fun updateMealNameV2(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
        @Valid @RequestBody request: UpdateMealNameRequest,
    ): MealV2Response = mealService.updateMealNameV2(request)

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
