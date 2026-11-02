package com.dandi.nyummy.worker.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Worker 서비스에서만 스케줄링을 켠다. API 서비스에서는 @Scheduled 메서드가 있어도 돌지 않는다.
 */
@Configuration
@ConditionalOnWorkerEnabled
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerConfig
