package com.dandi.nyummy.admin.controller

import com.dandi.nyummy.admin.config.AdminPaths
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminAnalysisFeedbacksResponse
import com.dandi.nyummy.admin.service.AdminAnalysisFeedbackService
import com.dandi.nyummy.security.AuthAdmin
import com.dandi.nyummy.security.CurrentAdmin
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Analysis Feedback", description = "어드민 영양 분석 평가 조회 API (어드민 서비스 전용)")
@RestController
@RequestMapping("${AdminPaths.PREFIX}/analysis-feedbacks")
@SecurityRequirements
@ConditionalOnAdminEnabled
class AdminAnalysisFeedbackController(private val adminAnalysisFeedbackService: AdminAnalysisFeedbackService) {

    @Operation(
        summary = "분석 평가 목록 조회",
        description = "사용자의 영양 분석 평가를 대상 식사와 함께 최신순으로 조회한다. 사용자가 지운 식사의 평가도 포함하고 " +
            "meal.deletedAt으로 구분한다. 식사의 메뉴명·영양은 평가 당시가 아니라 지금 값이다. 무한 스크롤용 cursor 방식이다. " +
            "첫 요청은 cursor 없이 보내고, 다음 요청부터는 직전 응답의 nextCursor를 cursor로 보낸다.",
    )
    @GetMapping
    fun getAnalysisFeedbacks(
        @CurrentAdmin admin: AuthAdmin,
        @Parameter(description = "이 별점 이하만 조회. 생략하면 모든 별점") @RequestParam(required = false) maxRating: Float?,
        @Parameter(description = "이유 여부. 생략하면 모두, true면 이유를 적은 평가만")
        @RequestParam(required = false)
        hasReason: Boolean?,
        @Parameter(description = "직전 응답의 nextCursor. 첫 요청에서는 생략한다.") @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "한 번에 조회할 개수 (1~50, 기본 20)")
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(50)
        size: Int,
    ): AdminAnalysisFeedbacksResponse =
        adminAnalysisFeedbackService.getAnalysisFeedbacks(admin.adminId, maxRating, hasReason, cursor, size)
}
