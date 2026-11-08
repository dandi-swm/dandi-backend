package com.dandi.nyummy.worker.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty

/**
 * 영양 분석 Worker(app.worker.enabled와 app.worker.analysis.enabled가 모두 true)에서만 빈을 만든다.
 * 다른 종류의 작업만 처리하는 Worker 서비스에는 분석 polling·실행기가 없다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnBooleanProperty(name = ["app.worker.enabled", "app.worker.analysis.enabled"])
annotation class ConditionalOnAnalysisWorkerEnabled
