package com.dandi.nyummy.worker.config

import com.dandi.nyummy.infra.ai.AiProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Worker 서비스에서만 스케줄링과 분석 실행기를 켠다. API 서비스에서는 @Scheduled 메서드가 있어도 돌지 않는다.
 */
@Configuration
@ConditionalOnWorkerEnabled
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerConfig {

    /**
     * 작업 한 건(S3 다운로드 + Gemini 호출 + 결과 저장)을 처리하는 실행기.
     *
     * 스레드 수는 Gemini 연결 수와 같다. 스레드가 연결보다 많으면 남는 스레드가 연결을 기다리다 실패한다.
     * 큐를 두지 않아(용량 0) 스레드가 모두 바쁘면 제출이 거부된다. polling은 activeCount로 빈 자리를 세고 그만큼만 가져가지만,
     * activeCount는 근사값이라 드물게 거부될 수 있다(기본 AbortPolicy가 TaskRejectedException으로 알린다).
     */
    @Bean
    fun analysisExecutor(aiProperties: AiProperties): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = aiProperties.maxConnections
        maxPoolSize = aiProperties.maxConnections
        queueCapacity = 0
        setThreadNamePrefix("analysis-")
    }
}
