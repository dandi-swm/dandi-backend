package com.dandi.nyummy.worker.dto

/**
 * Worker가 lease로 가져간 작업 한 건. 트랜잭션이 끝난 뒤 실행기 스레드로 넘기므로 엔티티가 아닌 값만 담는다.
 *
 * @property leaseCount 이번에 가져가며 올린 번호. 결과를 쓸 때 이 번호로 아직 이 Worker의 작업인지 확인한다
 * @property canRetry 이번 시도가 일시적인 이유로 실패하면 다시 시도할 수 있는지. 마지막 시도면 false
 */
data class MealAnalysisJobResult(
    val jobId: Long,
    val mealId: Long,
    val leaseCount: Int,
    val imageKey: String,
    val canRetry: Boolean,
)
