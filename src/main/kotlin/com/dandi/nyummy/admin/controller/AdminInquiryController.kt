package com.dandi.nyummy.admin.controller

import com.dandi.nyummy.admin.config.AdminPaths
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminInquiriesResponse
import com.dandi.nyummy.admin.dto.AdminInquiryResponse
import com.dandi.nyummy.admin.dto.UpdateInquiryAnswerRequest
import com.dandi.nyummy.admin.service.AdminInquiryService
import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.InquiryErrorCode
import com.dandi.nyummy.inquiry.enum.InquiryCategory
import com.dandi.nyummy.security.AuthAdmin
import com.dandi.nyummy.security.CurrentAdmin
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Inquiry", description = "어드민 문의함 조회 · 답변 API (어드민 서비스 전용)")
@RestController
@RequestMapping("${AdminPaths.PREFIX}/inquiries")
@SecurityRequirements
@ConditionalOnAdminEnabled
class AdminInquiryController(private val adminInquiryService: AdminInquiryService) {

    @Operation(
        summary = "문의 목록 조회",
        description = "모든 사용자의 문의를 최신순으로 조회한다. 사용자가 삭제한 문의는 제외한다. 무한 스크롤용 cursor 방식이다. " +
            "첫 요청은 cursor 없이 보내고, 다음 요청부터는 직전 응답의 nextCursor를 cursor로 보낸다.",
    )
    @GetMapping
    fun getInquiries(
        @Parameter(description = "문의 유형. 생략하면 모든 유형") @RequestParam(required = false) category: InquiryCategory?,
        @Parameter(description = "답변 여부. 생략하면 모두, false면 답변 대기만") @RequestParam(required = false) isAnswered: Boolean?,
        @Parameter(description = "직전 응답의 nextCursor. 첫 요청에서는 생략한다.") @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "한 번에 조회할 개수 (1~50, 기본 20)")
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(50)
        size: Int,
    ): AdminInquiriesResponse = adminInquiryService.getInquiries(category, isAnswered, cursor, size)

    @Operation(
        summary = "문의 상세 조회",
        description = "문의의 질문과 답변, 사용자의 답변 읽음 시각을 조회한다. 어드민이 조회해도 사용자의 읽음 상태는 바뀌지 않는다.",
    )
    @ApiErrorCodes(inquiry = [InquiryErrorCode.INQUIRY_NOT_FOUND])
    @GetMapping("/{inquiryId}")
    fun getInquiry(
        @Parameter(description = "문의 ID") @PathVariable("inquiryId") inquiryId: Long,
    ): AdminInquiryResponse = adminInquiryService.getInquiry(inquiryId)

    @Operation(
        summary = "답변 등록 · 수정",
        description = "답변이 없으면 등록하고 사용자에게 알림을 보낸다. 이미 있으면 제목·내용만 고친다. " +
            "수정은 오타 정정용이라 알림을 보내지 않고, 답변 시각과 사용자의 읽음 상태도 바꾸지 않는다.",
    )
    @ApiErrorCodes(inquiry = [InquiryErrorCode.INQUIRY_NOT_FOUND])
    @PutMapping("/{inquiryId}/answer")
    fun updateAnswer(
        @CurrentAdmin admin: AuthAdmin,
        @Parameter(description = "문의 ID") @PathVariable("inquiryId") inquiryId: Long,
        @Valid @RequestBody request: UpdateInquiryAnswerRequest,
    ): AdminInquiryResponse = adminInquiryService.updateAnswer(admin.adminId, inquiryId, request)
}
