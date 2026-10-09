package com.dandi.nyummy.meal.service

import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisClient
import com.dandi.nyummy.meal.queue.ClaimedMealAnalysis
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AnalysisHandler(
    private val analysisClient: NutritionAnalysisClient,
    private val analysisService: AnalysisService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // AI 호출에는 트랜잭션을 열지 않는다. 저장 실패는 분석 실패와 구분해 호출자에게 전파한다.
    fun handle(job: ClaimedMealAnalysis) {
        val result = try {
            analysisClient.analyzeNutrition(job.imageKey)
        } catch (e: Exception) {
            logger.error("영양 분석 실패: queueId={}, mealId={}", job.queueId, job.mealId, e)
            analysisService.failNutritionAnalysis(job.queueId)
            return
        }
        analysisService.completeNutritionAnalysis(job, result)
    }
}
