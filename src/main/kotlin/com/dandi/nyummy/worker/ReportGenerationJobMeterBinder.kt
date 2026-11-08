package com.dandi.nyummy.worker

import com.dandi.nyummy.report.repository.ReportGenerationJobRepository
import com.dandi.nyummy.worker.config.ConditionalOnReportWorkerEnabled
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.TimeGauge
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 리포트 생성 작업 큐가 밀리는지 보는 gauge를 등록한다. 리포트 Worker에서만 등록하고, 규칙은 [MealAnalysisJobMeterBinder]와 같다.
 */
@Component
@ConditionalOnReportWorkerEnabled
class ReportGenerationJobMeterBinder(private val reportGenerationJobRepository: ReportGenerationJobRepository) :
    MeterBinder {

    override fun bindTo(registry: MeterRegistry) {
        Gauge.builder("nyummy.report.generation.jobs.waiting", reportGenerationJobRepository) {
            it.countWaitingReportGenerationJobs().toDouble()
        }
            .description("Worker가 지금 가져갈 수 있는 리포트 생성 작업 수")
            .register(registry)

        TimeGauge.builder(
            "nyummy.report.generation.jobs.oldest.wait",
            reportGenerationJobRepository,
            TimeUnit.SECONDS,
        ) {
            (it.findOldestWaitingSeconds() ?: 0L).toDouble()
        }
            .description("가져갈 수 있는 리포트 생성 작업 중 가장 오래 기다린 시간")
            .register(registry)
    }
}
