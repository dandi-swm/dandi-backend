package com.dandi.nyummy.meal.scheduling

import com.dandi.nyummy.meal.config.MealOutboxProperties
import com.dandi.nyummy.meal.repository.MealOutboxRepository
import com.dandi.nyummy.meal.service.OutboxDispatchService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "app.meal.outbox",
    name = ["recovery-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class MealOutboxRecoveryScheduler(
    private val outboxRepository: MealOutboxRepository,
    private val dispatchService: OutboxDispatchService,
    private val properties: MealOutboxProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.meal.outbox.recovery-delay:3s}")
    fun recover() {
        // 배치 전체를 묶지 않고 각 dispatch 호출에서 독립적으로 커밋한다.
        for (outboxId in outboxRepository.findPendingIds(properties.batchSize)) {
            try {
                dispatchService.dispatch(outboxId)
            } catch (e: Exception) {
                logger.error("Outbox 복구 전달 실패: outboxId={}", outboxId, e)
            }
        }
    }
}
