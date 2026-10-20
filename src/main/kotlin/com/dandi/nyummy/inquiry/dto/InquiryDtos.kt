@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.inquiry.dto

import com.dandi.nyummy.inquiry.entity.Inquiry
import com.dandi.nyummy.inquiry.enum.InquiryCategory
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateInquiryRequest(
    val category: InquiryCategory,

    @field:NotBlank(message = "문의 제목은 필수입니다.")
    @field:Size(max = 100, message = "문의 제목은 100자 이하여야 합니다.")
    val questionTitle: String,

    @field:NotBlank(message = "문의 내용은 필수입니다.")
    @field:Size(max = 2000, message = "문의 내용은 2000자 이하여야 합니다.")
    val questionContent: String,

    val imageKey: String? = null,
)

data class UpdateInquiryRequest(
    val category: InquiryCategory,

    @field:NotBlank(message = "문의 제목은 필수입니다.")
    @field:Size(max = 100, message = "문의 제목은 100자 이하여야 합니다.")
    val questionTitle: String,

    @field:NotBlank(message = "문의 내용은 필수입니다.")
    @field:Size(max = 2000, message = "문의 내용은 2000자 이하여야 합니다.")
    val questionContent: String,

    val imageKey: String? = null,
)

data class InquiriesResponse(val inquiries: List<InquirySummaryResponse>, val nextCursor: Long?, val hasNext: Boolean)

data class InquirySummaryResponse(
    val inquiryId: Long,
    val category: InquiryCategory,
    val questionTitle: String,
    val createdAt: Instant,
    val isAnswered: Boolean,
    val hasUnreadAnswer: Boolean,
)

data class InquiryResponse(
    val inquiryId: Long,
    val category: InquiryCategory,
    val questionTitle: String,
    val questionContent: String,
    val imageUrl: String?,
    val createdAt: Instant,
    val isAnswered: Boolean,
    val answerTitle: String?,
    val answerContent: String?,
    val answeredAt: Instant?,
)

/**
 * 답변 저장 결과. (컨트롤러에 노출되지 않는 서비스 내부 반환값)
 *
 * @property isFirstAnswer 처음 등록이면 true. 호출부가 알림을 보낼지 정한다
 */
data class InquiryAnswerResult(val inquiry: Inquiry, val isFirstAnswer: Boolean)
