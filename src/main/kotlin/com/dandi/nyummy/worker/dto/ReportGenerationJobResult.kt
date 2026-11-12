package com.dandi.nyummy.worker.dto

import com.dandi.nyummy.language.enum.Language
import java.time.Instant
import java.time.LocalDate

/**
 * Worker가 가져간 리포트 생성 작업 한 건. 트랜잭션이 끝난 뒤 실행기 스레드로 넘기므로 엔티티가 아닌 값만 담는다.
 *
 * @property userId 실패 환불 트랜잭션이 첫 문장에서 코인 행을 잠글 때 쓴다(리포트를 먼저 읽지 않기 위해 여기 담는다)
 * @property leaseCount 이번에 가져가며 올린 번호. 최대 시도 수를 채워 가져가지 않은 작업이면 가져가기 전 번호다.
 *   결과를 쓸 때 이 번호로 아직 이 Worker의 작업인지 확인한다
 * @property canRetry 이번 시도가 실패하면 다시 시도할 수 있는지. 마지막 시도면 false
 * @property createdAt 작업이 쌓인 시각(= 구매 시각). 구매부터 결과까지 걸린 시간을 잴 때 쓴다
 * @property language 리포트를 구매한 요청의 앱 언어. 언어를 몰랐던 작업은 원본 언어다
 */
data class ReportGenerationJobResult(
    val jobId: Long,
    val reportId: Long,
    val userId: Long,
    val startDate: LocalDate,
    val leaseCount: Int,
    val canRetry: Boolean,
    val createdAt: Instant,
    val language: Language,
)

/**
 * lease 트랜잭션의 결과.
 *
 * @property leasedJobs 이번에 가져가 실행기에 넘길 작업
 * @property exhaustedJobs 최대 시도 수를 채웠는데 또 lease가 만료된 작업. 처리하던 Worker가 거듭 죽은 경우라 다시 가져가지 않고,
 *   lease 트랜잭션이 끝난 뒤 실패 환불 트랜잭션으로 FAILED로 끝낸다(코인 행을 먼저 잠가야 해서 lease 트랜잭션 안에서 하지 않는다)
 */
data class ReportGenerationLeaseResult(
    val leasedJobs: List<ReportGenerationJobResult>,
    val exhaustedJobs: List<ReportGenerationJobResult>,
)
