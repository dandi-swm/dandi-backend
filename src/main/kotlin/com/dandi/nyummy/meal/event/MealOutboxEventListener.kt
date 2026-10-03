package com.dandi.nyummy.meal.event

import com.dandi.nyummy.meal.service.OutboxDispatchService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class MealOutboxEventListener(private val dispatchService: OutboxDispatchService) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Async("outboxExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handle(event: MealAnalysisRequested) {
        try {
            dispatchService.dispatch(event.outboxId)
        } catch (e: Exception) {
            // 전달 실패는 미전달 상태로 남겨 복구 스케줄러가 다시 처리한다.
            logger.error("Outbox 즉시 전달 실패: outboxId={}", event.outboxId, e)
        }
    }
}
