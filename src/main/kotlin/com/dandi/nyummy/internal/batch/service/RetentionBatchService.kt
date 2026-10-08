package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.internal.batch.NotificationId
import com.dandi.nyummy.internal.batch.repository.PushDeduplicationRepository
import com.dandi.nyummy.notification.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class RetentionBatchService(
    private val catRepository: CatRepository,
    private val pushDeduplicationRepository: PushDeduplicationRepository,
    private val notificationService: NotificationService,
    private val clock: Clock,
    private val appLinkProperties: AppLinkProperties,
) {
    companion object {
        // TODO: 사용자별 timezone으로 수정
        private val ZONE = ZoneId.of("Asia/Seoul")

        /** 하루 1회 발송이므로 다음 날까지 덮는다. 2시간이면 같은 날 재시도에 중복이 뚫린다. */
        private val DEDUPLICATION_TIME_TO_LIVE = Duration.ofHours(25)

        private const val SLEEP_DAYS = 14L
        private val LOSE_WEIGHT_DAYS = setOf(3L, 6L, 9L, 12L)

        private val logger = LoggerFactory.getLogger(RetentionBatchService::class.java)
    }

    /**
     * 기록이 끊긴 사용자에게 복귀 유도 알림을 보낸다.
     *
     * 수신 거부자·기기 토큰 없는 사용자는 조회 쿼리가 걸러낸다.
     * 발송을 트랜잭션 밖에서 하는 이유는 [MealReminderService.sendMealReminders]와 같다.
     */
    fun sendDailyRetentionPushes() {
        val today = LocalDate.now(clock.withZone(ZONE))
        val targets = catRepository.getRetentionPushTargets()

        val messagesByToken = targets.mapNotNull { target ->
            val daysDiff = ChronoUnit.DAYS.between(target.lastMealAt.atZone(ZONE).toLocalDate(), today)
            val notificationId = NotificationId.createRetentionId(target.userId, today)

            convertPushMessage(notificationId, target.catName, daysDiff)
                ?.takeIf { pushDeduplicationRepository.markSent(notificationId, DEDUPLICATION_TIME_TO_LIVE) }
                ?.let { target.token to it }
        }

        val result = notificationService.sendPushes(messagesByToken.toMap())

        notificationService.deleteInvalidDeviceTokens(result.invalidTokens)

        logger.info(
            "리텐션 푸시 발송: target={}, sent={}, success={}, invalid={}",
            targets.size,
            messagesByToken.size,
            result.successCount,
            result.invalidTokens.size,
        )
    }

    /**
     * 굶은 일수에 따라 문구를 고른다.
     *
     * 위에서부터 먼저 맞는 하나만 보낸다. 전용 문구가 있는 날은 매일 문구를 대체하고,
     * 15일 이후로는 다시 매일 문구로 돌아간다.
     */
    private fun convertPushMessage(notificationId: String, catName: String, daysDiff: Long): PushMessage? = when {
        daysDiff <= 0L -> null

        daysDiff == SLEEP_DAYS -> PushMessage(
            type = PushType.RETENTION,
            notificationId = notificationId,
            title = "잠든 $catName 💤",
            body = "한 끼 기록하고 깨워주세요!",
            appLink = appLinkProperties.home,
        )

        daysDiff in LOSE_WEIGHT_DAYS -> PushMessage(
            type = PushType.RETENTION,
            notificationId = notificationId,
            title = "$catName 홀쭉해지고 있어요 🐾",
            body = "${daysDiff}일째 밥을 못 먹었어요ㅠㅠ",
            appLink = appLinkProperties.home,
        )

        else -> PushMessage(
            type = PushType.RETENTION,
            notificationId = notificationId,
            title = "$catName 밥 챙겨주라냥 🐾",
            body = "오늘 하루 밥을 깜빡했다냥!",
            appLink = appLinkProperties.home,
        )
    }
}
