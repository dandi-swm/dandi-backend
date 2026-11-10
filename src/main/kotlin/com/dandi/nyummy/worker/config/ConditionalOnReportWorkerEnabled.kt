package com.dandi.nyummy.worker.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty

/**
 * 리포트 생성 Worker(app.worker.enabled와 app.worker.report.enabled가 모두 true)에서만 빈을 만든다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnBooleanProperty(name = ["app.worker.enabled", "app.worker.report.enabled"])
annotation class ConditionalOnReportWorkerEnabled
