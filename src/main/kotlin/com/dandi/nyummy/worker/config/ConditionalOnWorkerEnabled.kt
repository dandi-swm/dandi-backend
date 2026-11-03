package com.dandi.nyummy.worker.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty

/**
 * Worker 서비스(app.worker.enabled=true)에서만 빈을 만든다. API 서비스에는 작업을 가져가는 polling·실행기가 없다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnBooleanProperty("app.worker.enabled")
annotation class ConditionalOnWorkerEnabled
