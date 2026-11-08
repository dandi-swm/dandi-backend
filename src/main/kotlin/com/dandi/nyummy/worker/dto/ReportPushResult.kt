package com.dandi.nyummy.worker.dto

import com.dandi.nyummy.report.enum.ReportStatus
import java.time.LocalDate

/**
 * 리포트를 COMPLETED·FAILED로 끝낸 뒤 결과 알림에 쓰는 값. 트랜잭션이 끝난 뒤 알림을 보내므로 엔티티가 아닌 값만 담는다.
 *
 * @property refundedCoinQuantity FAILED로 끝나며 돌려준 코인. 무료 리포트였거나 완료되었으면 0이다
 */
data class ReportPushResult(
    val reportId: Long,
    val userId: Long,
    val startDate: LocalDate,
    val status: ReportStatus,
    val refundedCoinQuantity: Int,
)
