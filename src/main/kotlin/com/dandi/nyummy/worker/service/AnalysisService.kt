package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.worker.config.ConditionalOnWorkerEnabled
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
@ConditionalOnWorkerEnabled
class AnalysisService(
    private val catRepository: CatRepository,
    private val notificationService: NotificationService,
    private val appLinkProperties: AppLinkProperties,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AnalysisService::class.java)
    }

    /**
     * 분석 결과를 푸시로 알린다. 상태가 이미 커밋된 뒤에 보내므로 발송이 실패해도 결과는 남는다.
     * 수신 거부·토큰 없음은 [NotificationService.sendServicePush]가 조용히 넘긴다.
     */
    private fun sendAnalysisPush(meal: Meal, status: MealStatus, analyzedAt: Instant) {
        try {
            val catName = catRepository.findByUserId(meal.userId)?.name
                ?: run {
                    logger.error("가입 시 생성되어야 할 고양이가 없습니다: userId={}", meal.userId)
                    return
                }

            val result = notificationService.sendServicePush(
                meal.userId,
                convertPushMessage(meal, status, catName, analyzedAt),
            )

            notificationService.deleteInvalidDeviceTokens(result.invalidTokens)
        } catch (e: RuntimeException) {
            logger.error("분석 결과 알림 발송 실패: mealId={}, status={}", meal.id, status, e)
        }
    }

    /**
     * 성공·실패에 따라 알림 내용을 고른다.
     *
     * 링크는 둘 다 식사 상세다. 성공은 칼로리를 확인하러, 실패는 재시도하러 같은 화면으로 간다.
     */
    private fun convertPushMessage(meal: Meal, status: MealStatus, catName: String, analyzedAt: Instant): PushMessage =
        if (status == MealStatus.COMPLETED) {
            PushMessage(
                type = PushType.ANALYSIS_COMPLETED,
                notificationId = NotificationId.createAnalysisId(meal.id, status, analyzedAt),
                title = "$catName 맛있게 먹었다냥 🐾",
                body = "${meal.name} 분석이 끝났어요. 칼로리 확인해줘!",
                appLink = appLinkProperties.createMealDetailLink(meal.id),
            )
        } else {
            PushMessage(
                type = PushType.ANALYSIS_FAILED,
                notificationId = NotificationId.createAnalysisId(meal.id, status, analyzedAt),
                title = "사진을 못 알아봤다냥 🐾",
                body = "다시 시도해줄래?",
                appLink = appLinkProperties.createMealDetailLink(meal.id),
            )
        }
}
