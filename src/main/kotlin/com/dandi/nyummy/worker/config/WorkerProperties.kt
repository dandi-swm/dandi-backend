package com.dandi.nyummy.worker.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Worker 설정. Worker는 같은 이미지를 별도 ECS 서비스로 띄우고, 그 서비스에서만 enabled를 켠다.
 *
 * @property enabled Worker 빈(polling·실행기)을 만든다. API 서비스는 기본값(false)이라 Worker 설정이 필요 없다
 */
@ConfigurationProperties(prefix = "app.worker")
data class WorkerProperties(val enabled: Boolean = false)
