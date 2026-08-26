package com.dandi.nyummy.meal.controller

import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.DailyMealsResponse
import com.dandi.nyummy.meal.dto.MealResponse
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.dto.MonthlyMealsResponse
import com.dandi.nyummy.meal.dto.UploadImageRequest
import com.dandi.nyummy.meal.dto.UploadImageResponse
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
class MealController(private val mealService: MealService, private val analysisService: AnalysisService) {

    @Operation(
        summary = "월간 식사 캘린더 조회",
        description = "해당 연·월의 캘린더 범위(주 시작 일요일, 앞뒤 달 날짜 포함)에 대해 날짜별 하루 평가와 음식 아이콘 목록을 조회한다.",
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

    @Operation(summary = "식사 단건 조회", description = "식사 하나의 상세 정보(이름, 시각, 상태, 영양, 이미지 URL)를 조회한다.")
    @GetMapping("/{mealId}")
    fun getMeal(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
    ): MealResponse = mealService.getMeal(user.userId, mealId)

    @Operation(summary = "식사 이름 수정", description = "식사의 이름을 수정하고 수정된 식사 정보를 반환한다.")
    @PutMapping("/{mealId}")
    fun updateMeal(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable("mealId") mealId: Long,
        @Parameter(description = "변경할 식사 이름") @RequestParam name: String,
    ): MealResponse = mealService.updateMeal(user.userId, mealId, name)

    @Operation(summary = "식사 삭제", description = "식사 기록을 삭제한다.")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
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
    @PostMapping
    fun createMeal(@CurrentUser user: AuthUser, @Valid @RequestBody request: CreateMealRequest): MealStatusResponse =
        mealService.createMeal(user.userId, request)

    @Operation(
        summary = "영양 분석 상태 조회",
        description = "식사의 영양 분석 상태(WAITING/ANALYZING/COMPLETED/FAILED/UNKNOWN)를 조회한다. " +
            "영양 정보는 포함하지 않으며, COMPLETED 이후 식사 단건 조회 API로 가져온다.",
    )
    @GetMapping("/{mealId}/analysis")
    fun getStatus(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable @NotNull @Valid mealId: Long,
    ): MealStatusResponse = analysisService.getStatus(user.userId, mealId)

    @Operation(summary = "영양 분석 재시도", description = "실패한 식사의 영양 분석을 다시 시도하고 변경된 분석 상태를 반환한다.")
    @PostMapping("/{mealId}/analysis")
    fun retryAnalysis(
        @CurrentUser user: AuthUser,
        @Parameter(description = "식사 ID") @PathVariable @NotNull @Valid mealId: Long,
    ): MealStatusResponse = analysisService.retryNutritionAnalysis(user.userId, mealId)

    @Operation(
        summary = "이미지 업로드 URL 발급",
        description = "식사 이미지를 외부 스토리지에 직접 업로드할 수 있는 presigned URL과 이미지 키를 발급한다.",
    )
    @PostMapping("/images/presigned-url")
    fun getUploadUrl(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: UploadImageRequest,
    ): UploadImageResponse = mealService.createUploadUrl(user.userId, request)
}
