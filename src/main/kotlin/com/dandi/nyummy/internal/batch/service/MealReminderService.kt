package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.internal.batch.NotificationId
import com.dandi.nyummy.internal.batch.repository.PushDeduplicationRepository
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.user.repository.ProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class MealReminderService(
    private val profileRepository: ProfileRepository,
    private val mealRepository: MealRepository,
    private val pushDeduplicationRepository: PushDeduplicationRepository,
    private val notificationService: NotificationService,
    private val clock: Clock,
    private val appLinkProperties: AppLinkProperties,
) {
    companion object {
        // TODO: 사용자별 timezone으로 수정
        private val ZONE = ZoneId.of("Asia/Seoul")
        private val DEDUPLICATION_TIME_TO_LIVE = Duration.ofHours(25)
        private val logger = LoggerFactory.getLogger(MealReminderService::class.java)
    }

    /**
     * 설정한 식사 시각에 기록이 없는 사용자에게 리마인더를 보낸다.
     *
     * 젠킨스는 트리거만 하고 대상 시각은 서버가 자기 시계에서 끌어온다. 젠킨스 서버의 타임존에
     * 의존하지 않고, 테스트에서 [Clock]으로 고정할 수 있다.
     * [hour]를 넘기면 그 시각으로 발송한다 — 누락분 재발송이나 디버깅용이다.
     *
     * @param hour 대상 시각(0~23). 생략하면 현재 시각
     */
    fun sendMealReminders(hour: Int? = null) {
        val zonedClock = clock.withZone(ZONE)
        val targetHour = hour ?: LocalTime.now(zonedClock).hour
        val today = LocalDate.now(zonedClock)
        val oneHourAgo = Instant.now(clock).minus(1, ChronoUnit.HOURS)

        // 최근 한 시간 안에 기록했으면 리마인드 X
        val targets = profileRepository.getMealReminderTargets(targetHour)
            .filterNot {
                mealRepository.existsCompletedMealAfter(it.userId, MealStatus.COMPLETED, oneHourAgo)
            }

        if (targets.isEmpty()) {
            logger.info("식사 리마인더 대상 없음: hour={}", targetHour)
            return
        }

        val messagesByToken = targets.mapNotNull { target ->
            val mealTime = target.convertMealTime(targetHour)
            val notificationId = NotificationId.createMealReminderId(target.userId, today, mealTime)

            // 젠킨스 재시도나 잡 중복 실행에서 알림이 두 번 가는 것을 방지
            if (!pushDeduplicationRepository.markSent(notificationId, DEDUPLICATION_TIME_TO_LIVE)) {
                return@mapNotNull null
            }

            target.token to PushMessage(
                type = PushType.MEAL_REMINDER,
                notificationId = notificationId,
                title = "집사, ${mealTime.label}은 먹었어? \uD83D\uDC3E",
                body = "${target.catName} 너무 배고프다냥. 사진 한 장만 찍어줘!",
                appLink = appLinkProperties.home,
            )
        }

        val result = notificationService.sendPushes(messagesByToken.toMap())

        notificationService.deleteInvalidDeviceTokens(result.invalidTokens)

        logger.info(
            "식사 리마인더 발송: hour={}, target={}, sent={}, success={}, invalid={}",
            targetHour,
            targets.size,
            messagesByToken.size,
            result.successCount,
            result.invalidTokens.size,
        )
    }
}
