package com.dandi.nyummy.worker.config

import com.dandi.nyummy.infra.ai.AiProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Worker 서비스에서만 스케줄링과 작업 종류별 실행기를 켠다. API 서비스에서는 @Scheduled 메서드가 있어도 돌지 않는다.
 *
 * 켜진 종류의 스레드 수 합이 Gemini 연결 수를 넘으면 앱이 뜨지 않는다. 스레드가 연결보다 많으면 남는 스레드가 연결을
 * 기다리다 실패한다. dev처럼 한 서비스에서 여러 종류를 함께 켤 때 이 합을 맞춰야 한다.
 */
@Configuration
@ConditionalOnWorkerEnabled
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerConfig(workerProperties: WorkerProperties, aiProperties: AiProperties) {

    init {
        val maxThreads = workerProperties.calculateEnabledMaxThreads()
        require(maxThreads <= aiProperties.maxConnections) {
            "켜진 Worker의 스레드 수 합($maxThreads)이 app.ai.max-connections(${aiProperties.maxConnections})보다 많습니다."
        }
    }

    /**
     * 분석 작업 한 건(S3 다운로드 + Gemini 호출 + 결과 저장)을 처리하는 실행기.
     *
     * 큐를 두지 않아(용량 0) 스레드가 모두 바쁘면 제출이 거부된다. polling은 activeCount로 빈 자리를 세고 그만큼만 가져가지만,
     * activeCount는 근사값이라 드물게 거부될 수 있다(기본 AbortPolicy가 TaskRejectedException으로 알린다).
     */
    @Bean
    @ConditionalOnAnalysisWorkerEnabled
    fun analysisExecutor(workerProperties: WorkerProperties): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = workerProperties.analysis.maxThreads
        maxPoolSize = workerProperties.analysis.maxThreads
        queueCapacity = 0
        setThreadNamePrefix("analysis-")
    }

    /**
     * 리포트 한 건(식사 집계 + Gemini 호출 + 결과 저장)을 처리하는 실행기. 큐를 두지 않는 이유는 [analysisExecutor]와 같다.
     */
    @Bean
    @ConditionalOnReportWorkerEnabled
    fun reportExecutor(workerProperties: WorkerProperties): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = workerProperties.report.maxThreads
        maxPoolSize = workerProperties.report.maxThreads
        queueCapacity = 0
        setThreadNamePrefix("report-")
    }
}
