package com.dandi.nyummy.worker

import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 큐 gauge가 읽을 때마다 리포지토리에서 값을 가져오는지, 기다리는 작업이 없을 때 0을 내는지 다룬다.
 */
class MealAnalysisJobMeterBinderTest {

    private val mealAnalysisJobRepository = mockk<MealAnalysisJobRepository>()
    private val registry = SimpleMeterRegistry()

    init {
        MealAnalysisJobMeterBinder(mealAnalysisJobRepository).bindTo(registry)
    }

    private fun readWaiting(): Double = registry.get("nyummy.meal.analysis.jobs.waiting").gauge().value()

    private fun readOldestWaitSeconds(): Double =
        registry.get("nyummy.meal.analysis.jobs.oldest.wait").timeGauge().value(TimeUnit.SECONDS)

    @Test
    fun `대기 작업 수는 읽을 때마다 리포지토리에서 센다`() {
        every { mealAnalysisJobRepository.countWaitingMealAnalysisJobs() } returnsMany listOf(3L, 7L)

        assertEquals(3.0, readWaiting())
        assertEquals(7.0, readWaiting())
    }

    @Test
    fun `가장 오래 기다린 시간은 초 단위 값을 그대로 낸다`() {
        every { mealAnalysisJobRepository.findOldestWaitingSeconds() } returns 120L

        assertEquals(120.0, readOldestWaitSeconds())
    }

    @Test
    fun `기다리는 작업이 없으면 가장 오래 기다린 시간은 0이다`() {
        every { mealAnalysisJobRepository.findOldestWaitingSeconds() } returns null

        assertEquals(0.0, readOldestWaitSeconds())
    }
}
