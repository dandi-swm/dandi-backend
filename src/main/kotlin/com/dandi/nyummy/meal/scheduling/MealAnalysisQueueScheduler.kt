package com.dandi.nyummy.meal.scheduling

import com.dandi.nyummy.meal.queue.DbMealAnalysisConsumer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "app.meal.analysis",
    name = ["polling-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class MealAnalysisQueueScheduler(private val consumer: DbMealAnalysisConsumer) {
    @Scheduled(fixedDelayString = "\${app.meal.analysis.poll-delay:1s}")
    fun poll() {
        consumer.poll()
    }
}
