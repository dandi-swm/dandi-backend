@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.internal.batch.dto

import com.dandi.nyummy.report.enum.ReportMismatchCategory
import java.time.Instant

/**
 * 리포트 대조 결과. Jenkins 잡이 [mismatchCount]가 0이 아니면 실패로 끝내 알린다.
 *
 * @property mismatchCount 모든 종류의 불일치 리포트 수 합. 종류마다 최대 [ReportMismatchResponse.reportIds] 크기까지만 센다
 */
data class ReportReconciliationResponse(
    val checkedAt: Instant,
    val mismatchCount: Int,
    val mismatches: List<ReportMismatchResponse>,
)

/**
 * @property reportIds 불일치가 있는 리포트 id. 많으면 앞의 일부만 담는다
 */
data class ReportMismatchResponse(val category: ReportMismatchCategory, val reportIds: List<Long>)
