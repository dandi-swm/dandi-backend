package com.dandi.nyummy.report.mapper

import com.dandi.nyummy.report.dto.ReportResponse
import com.dandi.nyummy.report.dto.ReportSummaryResponse
import com.dandi.nyummy.report.entity.Report
import tools.jackson.databind.JsonNode

fun Report.toReportSummaryResponse(isRefunded: Boolean): ReportSummaryResponse = ReportSummaryResponse(
    reportId = this.id,
    startDate = this.startDate,
    endDate = this.endDate,
    status = this.status,
    title = this.title,
    isUnread = this.isUnread,
    isRefunded = isRefunded,
    createdAt = this.createdAt,
)

fun Report.toReportResponse(content: JsonNode?): ReportResponse = ReportResponse(
    reportId = this.id,
    startDate = this.startDate,
    endDate = this.endDate,
    status = this.status,
    title = this.title,
    createdAt = this.createdAt,
    completedAt = this.completedAt,
    viewType = this.viewType,
    content = content,
)
