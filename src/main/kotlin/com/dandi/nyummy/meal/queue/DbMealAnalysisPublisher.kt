package com.dandi.nyummy.meal.queue

import com.dandi.nyummy.meal.entity.MealAnalysisQueue
import com.dandi.nyummy.meal.repository.MealAnalysisQueueRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Component
class DbMealAnalysisPublisher(private val queueRepository: MealAnalysisQueueRepository, private val clock: Clock) :
    MealAnalysisPublisher {
    // 큐 저장은 Outbox 전달 완료 변경과 동일한 트랜잭션에 참여한다.
    @Transactional(propagation = Propagation.MANDATORY)
    override fun publish(message: MealAnalysisMessage) {
        queueRepository.save(
            MealAnalysisQueue(
                outboxId = message.eventId,
                mealId = message.mealId,
                createdAt = Instant.now(clock),
            ),
        )
    }
}
