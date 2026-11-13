package com.dandi.nyummy.meal.config

import com.dandi.nyummy.infra.ai.AiProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.support.ContextPropagatingTaskDecorator
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * 식사 기록 요청 안에서 음식 인식을 돌리는 실행기를 만든다.
 *
 * 인식 스레드 수가 Gemini 연결 수를 넘으면 앱이 뜨지 않는다. 스레드가 연결보다 많으면 남는 스레드가 연결을 기다리느라
 * 응답 기한을 쓴다. dev처럼 한 서비스에서 Worker도 함께 켜면 둘이 같은 연결 풀을 나눠 쓴다.
 */
@Configuration
class MealRecognitionConfig(mealProperties: MealProperties, aiProperties: AiProperties) {

    init {
        val maxThreads = mealProperties.recognition.maxThreads
        require(maxThreads <= aiProperties.maxConnections) {
            "app.meal.recognition.max-threads($maxThreads)가 app.ai.max-connections(${aiProperties.maxConnections})보다 많습니다."
        }
    }

    /**
     * 식사 기록 요청 한 건의 음식 인식(Gemini 호출)을 돌리는 실행기. 요청 스레드는 응답 기한까지 남은 시간만 기다리고,
     * 넘으면 결과를 버리고 Worker의 인식 작업으로 넘긴다.
     *
     * 큐를 두지 않는다(용량 0). 스레드가 모두 바쁘면 제출이 거부되고(TaskRejectedException), 그 요청도 기다리지 않고
     * Worker의 인식 작업으로 넘어간다. 큐에서 기다린 시간만큼 응답 기한이 줄기 때문이다.
     *
     * 요청 스레드의 관측 컨텍스트를 실행기 스레드로 옮긴다. 옮기지 않으면 Gemini 호출이 요청과 다른 trace로 끊겨 보인다.
     */
    @Bean
    fun mealRecognitionExecutor(mealProperties: MealProperties): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = mealProperties.recognition.maxThreads
            maxPoolSize = mealProperties.recognition.maxThreads
            queueCapacity = 0
            setThreadNamePrefix("recognition-")
            setTaskDecorator(ContextPropagatingTaskDecorator())
        }
}
