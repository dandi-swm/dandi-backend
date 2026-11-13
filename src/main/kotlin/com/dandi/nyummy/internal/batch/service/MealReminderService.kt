package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.internal.batch.repository.PushDeduplicationRepository
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.user.repository.ProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.TimeSource

@Service
class MealReminderService(
    private val profileRepository: ProfileRepository,
    private val mealRepository: MealRepository,
    private val pushDeduplicationRepository: PushDeduplicationRepository,
    private val notificationService: NotificationService,
    private val clock: Clock,
    private val appLinkProperties: AppLinkProperties,
    private val messageSource: MessageSource,
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
     * 사용자가 정한 시각에 보내는 서비스 알림이라 밤에도 보낸다. 문구에 상품이나 혜택을 넣으면 광고성 정보로 볼 수 있어
     * 밤 시간(21시~8시)에는 별도 동의가 필요해지므로 넣지 않는다.
     *
     * 문구는 기기 토큰에 저장한 앱 언어로 고른다. 요청 없이 보내는 알림이라 물어볼 헤더가 없다.
     *
     * @param hour 대상 시각(0~23). 생략하면 현재 시각
     */
    fun sendMealReminders(hour: Int? = null) {
        // 소요시간은 주입된 Clock이 아니라 단조 시계로 잰다
        val startedAt = TimeSource.Monotonic.markNow()

        val zonedClock = clock.withZone(ZONE)
        val targetHour = hour ?: LocalTime.now(zonedClock).hour
        val today = LocalDate.now(zonedClock)
        val oneHourAgo = Instant.now(clock).minus(1, ChronoUnit.HOURS)

        // 최근 한 시간 안에 음식을 기록했으면 리마인드하지 않는다. 영양 계산이 끝났는지는 보지 않는다.
        // 알림의 뜻이 "아직 안 찍었으면 찍어라"라, 인식 중이거나 계산을 기다리는 식사도 기록한 것으로 본다.
        // 실패한 식사만 뺀다. 음식이 아닌 사진만 올린 사용자는 아직 기록하지 않은 것이다.
        val targets = profileRepository.findMealReminderTargets(targetHour)
            .filterNot {
                mealRepository.existsMealAfter(it.userId, MealStatus.FAILED, oneHourAgo)
            }

        if (targets.isEmpty()) {
            logger.info("식사 리마인더 대상 없음: hour={}, elapsed={}ms", targetHour, startedAt.elapsedNow().inWholeMilliseconds)
            return
        }

        val messagesByToken = targets.mapNotNull { target ->
            val mealTime = target.convertMealTime(targetHour)
            val notificationId = NotificationId.createMealReminderId(target.userId, today, mealTime)

            // 젠킨스 재시도나 잡 중복 실행에서 알림이 두 번 가는 것을 방지
            if (!pushDeduplicationRepository.createSentMark(notificationId, DEDUPLICATION_TIME_TO_LIVE)) {
                return@mapNotNull null
            }

            val language = target.language ?: Language.ORIGINAL_LANGUAGE
            val mealTimeLabel = messageSource.getMessage("mealTime.${mealTime.name}", language)

            target.token to PushMessage(
                userId = target.userId,
                type = PushType.MEAL_REMINDER,
                notificationId = notificationId,
                title = messageSource.getMessage("push.mealReminder.title", language, mealTimeLabel),
                body = messageSource.getMessage("push.mealReminder.body", language, target.catName),
                appLink = appLinkProperties.home,
            )
        }.toMap()

        val result = notificationService.sendPushes(messagesByToken)

        notificationService.deleteInvalidDeviceTokens(result.invalidTokens)
        pushDeduplicationRepository.deleteSentMarks(result.getRetryableNotificationIds(messagesByToken))

        logger.info(
            "식사 리마인더 발송: hour={}, target={}, sent={}, success={}, invalid={}, retryable={}, elapsed={}ms",
            targetHour,
            targets.size,
            messagesByToken.size,
            result.successCount,
            result.invalidTokens.size,
            result.retryableTokens.size,
            startedAt.elapsedNow().inWholeMilliseconds,
        )
    }
}
