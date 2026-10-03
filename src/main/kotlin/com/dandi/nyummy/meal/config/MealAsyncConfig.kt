package com.dandi.nyummy.meal.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
@EnableAsync
@EnableScheduling
class MealAsyncConfig {
    @Bean
    fun mealAnalysisExecutor(properties: MealAnalysisProperties): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = properties.concurrency
            maxPoolSize = properties.concurrency
            // 실행 자리 제한이 제출 수를 제한한다. 큐는 Worker 반환 직후의 인계 경합만 흡수한다.
            queueCapacity = properties.concurrency
            setThreadNamePrefix("meal-analysis-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(40)
        }

    @Bean
    fun outboxExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        queueCapacity = 100
        setThreadNamePrefix("meal-outbox-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(10)
    }

    @Bean
    fun taskScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 2
        setThreadNamePrefix("meal-scheduler-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(10)
    }
}
