package com.dandi.nyummy.meal.service

import com.dandi.nyummy.meal.queue.MealAnalysisMessage
import com.dandi.nyummy.meal.queue.MealAnalysisPublisher
import com.dandi.nyummy.meal.repository.MealOutboxRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class OutboxDispatchService(
    private val outboxRepository: MealOutboxRepository,
    private val publisher: MealAnalysisPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun dispatch(outboxId: Long) {
        val outbox = outboxRepository.findByIdForUpdate(outboxId) ?: return
        if (outbox.publishedAt != null) return

        publisher.publish(MealAnalysisMessage(outbox.id, outbox.mealId))
        outbox.markPublished(Instant.now(clock))
    }
}
