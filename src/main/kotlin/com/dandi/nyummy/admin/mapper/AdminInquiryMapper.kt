package com.dandi.nyummy.admin.mapper

import com.dandi.nyummy.admin.dto.AdminInquiryResponse
import com.dandi.nyummy.admin.dto.AdminInquirySummaryResponse
import com.dandi.nyummy.inquiry.entity.Inquiry

fun Inquiry.toAdminInquirySummaryResponse(): AdminInquirySummaryResponse = AdminInquirySummaryResponse(
    inquiryId = this.id,
    userId = this.userId,
    category = this.category,
    questionTitle = this.questionTitle,
    createdAt = this.createdAt,
    isAnswered = this.isAnswered,
    answeredAt = this.answeredAt,
)

fun Inquiry.toAdminInquiryResponse(imageUrl: String?): AdminInquiryResponse = AdminInquiryResponse(
    inquiryId = this.id,
    userId = this.userId,
    category = this.category,
    questionTitle = this.questionTitle,
    questionContent = this.questionContent,
    imageUrl = imageUrl,
    appVersion = this.appVersion,
    createdAt = this.createdAt,
    isAnswered = this.isAnswered,
    answerTitle = this.answerTitle,
    answerContent = this.answerContent,
    answeredAt = this.answeredAt,
    answerReadAt = this.answerReadAt,
)
