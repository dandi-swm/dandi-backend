package com.dandi.nyummy.worker

import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.worker.config.ConditionalOnAnalysisWorkerEnabled
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.TimeGauge
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 분석 작업 큐가 밀리는지 보는 gauge를 등록한다. Worker 서비스에서만 등록한다.
 *
 * 값은 지표를 내보낼 때마다 DB에서 읽는다(내보내는 주기마다 짧은 쿼리 2개). polling 주기와는 상관없다.
 * 큐는 Worker가 모두 함께 쓰므로 Worker가 여럿이면 같은 값을 낸다. 대시보드에서는 max로 본다.
 *
 * 미뤄 둔 작업은 가져갈 수 있게 되기 전까지 두 gauge에 들어가지 않는다. 사용자가 음식 목록을 고칠 시간을 기다리는 v2 영양 작업이
 * 그렇다. 그 시간이 지나 가져갈 수 있게 되면 그때부터 기다린 시간만 센다. 그래서 두 gauge는 Worker가 밀린 정도만 보여 준다.
 */
@Component
@ConditionalOnAnalysisWorkerEnabled
class MealAnalysisJobMeterBinder(private val mealAnalysisJobRepository: MealAnalysisJobRepository) : MeterBinder {

    override fun bindTo(registry: MeterRegistry) {
        Gauge.builder("nyummy.meal.analysis.jobs.waiting", mealAnalysisJobRepository) {
            it.countWaitingMealAnalysisJobs().toDouble()
        }
            .description("Worker가 지금 가져갈 수 있는 분석 작업 수. 미뤄 둔 작업은 그 시간이 지나야 센다")
            .register(registry)

        TimeGauge.builder("nyummy.meal.analysis.jobs.oldest.wait", mealAnalysisJobRepository, TimeUnit.SECONDS) {
            // 가져갈 작업이 없으면 기다리는 작업도 없으므로 0이다.
            (it.findOldestWaitingSeconds() ?: 0L).toDouble()
        }
            .description("가져갈 수 있는 분석 작업 중 가장 오래 기다린 시간. 미뤘던 작업은 가져갈 수 있게 된 때부터 센다")
            .register(registry)
    }
}
