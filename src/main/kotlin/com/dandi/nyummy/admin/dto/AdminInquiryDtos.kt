@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.admin.dto

import com.dandi.nyummy.inquiry.enum.InquiryCategory
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class UpdateInquiryAnswerRequest(
    @field:NotBlank(message = "답변 제목은 필수입니다.")
    @field:Size(max = 100, message = "답변 제목은 100자 이하여야 합니다.")
    val answerTitle: String,

    @field:NotBlank(message = "답변 내용은 필수입니다.")
    @field:Size(max = 2000, message = "답변 내용은 2000자 이하여야 합니다.")
    val answerContent: String,
)

data class AdminInquiriesResponse(
    val inquiries: List<AdminInquirySummaryResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

data class AdminInquirySummaryResponse(
    val inquiryId: Long,
    val userId: Long,
    val category: InquiryCategory,
    val questionTitle: String,
    val createdAt: Instant,
    val isAnswered: Boolean,
    val answeredAt: Instant?,
)

data class AdminInquiryResponse(
    val inquiryId: Long,
    val userId: Long,
    val category: InquiryCategory,
    val questionTitle: String,
    val questionContent: String,
    val imageUrl: String?,
    val appVersion: String?,
    val createdAt: Instant,
    val isAnswered: Boolean,
    val answerTitle: String?,
    val answerContent: String?,
    val answeredAt: Instant?,
    val answerReadAt: Instant?,
)
