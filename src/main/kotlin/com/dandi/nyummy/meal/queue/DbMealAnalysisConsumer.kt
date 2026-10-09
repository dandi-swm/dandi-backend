package com.dandi.nyummy.meal.queue

import com.dandi.nyummy.meal.config.MealAnalysisProperties
import com.dandi.nyummy.meal.service.AnalysisHandler
import com.dandi.nyummy.meal.service.AnalysisService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Component
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

@Component
class DbMealAnalysisConsumer(
    private val analysisService: AnalysisService,
    private val handler: AnalysisHandler,
    @param:Qualifier("mealAnalysisExecutor") private val executor: TaskExecutor,
    properties: MealAnalysisProperties,
) {
    private val slots = Semaphore(properties.concurrency)
    private val logger = LoggerFactory.getLogger(javaClass)

    fun poll() {
        var reserved = 0
        while (slots.tryAcquire()) reserved++
        if (reserved == 0) return

        val jobs = try {
            analysisService.startNutritionAnalyses(reserved)
        } catch (e: Exception) {
            slots.release(reserved)
            logger.error("분석 작업 선점 실패", e)
            return
        }
        slots.release(reserved - jobs.size)

        for (job in jobs) {
            try {
                executor.execute {
                    try {
                        handler.handle(job)
                    } catch (e: Exception) {
                        // 저장 실패 작업은 PROCESSING으로 남는다. 자동 재분석하지 않는다.
                        logger.error("분석 결과 처리 실패: queueId={}", job.queueId, e)
                    } finally {
                        slots.release()
                    }
                }
            } catch (e: RejectedExecutionException) {
                try {
                    logger.error("분석 작업 제출 실패: queueId={}", job.queueId, e)
                    analysisService.failNutritionAnalysis(job.queueId)
                } catch (failure: Exception) {
                    logger.error("제출 실패 상태 저장 실패: queueId={}", job.queueId, failure)
                } finally {
                    slots.release()
                }
            }
        }
    }
}
