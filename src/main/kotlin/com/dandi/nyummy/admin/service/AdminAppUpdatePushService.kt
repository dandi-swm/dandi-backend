package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AppUpdatePushResponse
import com.dandi.nyummy.admin.dto.AppUpdatePushTargetResult
import com.dandi.nyummy.admin.dto.SendAppUpdatePushRequest
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.internal.batch.repository.PushDeduplicationRepository
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.repository.DeviceTokenRepository
import com.dandi.nyummy.notification.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.TimeSource

@Service
@ConditionalOnAdminEnabled
class AdminAppUpdatePushService(
    private val deviceTokenRepository: DeviceTokenRepository,
    private val pushDeduplicationRepository: PushDeduplicationRepository,
    private val notificationService: NotificationService,
    private val appLinkProperties: AppLinkProperties,
    private val messageSource: MessageSource,
    private val clock: Clock,
) {
    companion object {
        // TODO: 사용자별 timezone으로 수정
        private val ZONE = ZoneId.of("Asia/Seoul")

        /** 보낼 수 있는 시각(KST). 서비스 알림이라 법적 제한은 없지만, 운영자 실수로 밤에 전체 알림이 나가지 않게 막는다. */
        private val PUSH_TIMES = LocalTime.of(8, 0)..<LocalTime.of(21, 0)

        /** 다시 눌러도 이미 받은 사용자를 건너뛰는 기간. 출시 직후 며칠 안에 다시 누르는 경우를 덮는다. */
        private val DEDUPLICATION_TIME_TO_LIVE = Duration.ofDays(7)

        // 한 묶음의 대상 수. FCM sendEach 상한(500)과 같아 한 묶음이 FCM 호출 한 번이 된다
        private const val PAGE_SIZE = 500

        private val logger = LoggerFactory.getLogger(AdminAppUpdatePushService::class.java)
    }

    /**
     * 서비스 알림을 켠 그 플랫폼 사용자 전체에게 새 버전이 나왔다고 알린다.
     * 링크는 플랫폼과 상관없이 `/app-update` 앱 링크이고, 앱이 받아 자기 스토어를 연다.
     * 스토어 주소는 플랫폼마다 달라(패키지명·App Store 앱 id) 앱이 더 잘 알기 때문이다.
     *
     * 사용자의 앱 버전은 보지 않는다. 기기 토큰 등록이 앞으로 덜 잦아지면 저장한 버전이 늦게 바뀌어 거르는 의미가 없어서다.
     * 그래서 이미 업데이트한 사용자도 받는다.
     *
     * 요청 안에서 끝까지 보낸다. 작업 테이블을 두지 않는 대신 출시(플랫폼·버전)마다 중복 발송 표시를 남겨,
     * 두 번 누르거나 중간에 끊겨 다시 눌러도 못 받은 사용자에게만 간다.
     * 대상을 묶음으로 나눠 보내는 이유와 발송을 트랜잭션 밖에서 하는 이유는 [MealReminderService.sendMealReminders]와 같다.
     */
    fun sendAppUpdatePushes(adminId: Long, request: SendAppUpdatePushRequest): AppUpdatePushResponse {
        validatePushTime()
        // 소요시간은 주입된 Clock이 아니라 단조 시계로 잰다
        val startedAt = TimeSource.Monotonic.markNow()

        var cursor = 0L
        var total = AppUpdatePushResponse.EMPTY

        do {
            val page = deviceTokenRepository.findAppUpdatePushTargetsAfterCursor(
                platform = request.platform,
                cursor = cursor,
                pageable = PageRequest.of(0, PAGE_SIZE),
            )

            if (!page.hasContent()) {
                break
            }

            cursor = page.content.last().userId
            total += sendAppUpdatePushPage(page.content, request)
        } while (page.hasNext())

        logger.info(
            "앱 업데이트 알림 발송: adminId={}, platform={}, appVersion={}, target={}, sent={}, success={}, " +
                "invalid={}, retryable={}, elapsed={}ms",
            adminId,
            request.platform,
            request.appVersion,
            total.targetCount,
            total.sentCount,
            total.successCount,
            total.invalidCount,
            total.retryableCount,
            startedAt.elapsedNow().inWholeMilliseconds,
        )

        return total
    }

    private fun validatePushTime() {
        val now = LocalTime.now(clock.withZone(ZONE))

        if (now !in PUSH_TIMES) {
            throw BusinessException(AdminErrorCode.PUSH_NOT_ALLOWED_TIME)
        }
    }

    /**
     * 한 묶음을 보낸다. 무효 토큰 삭제와 재시도 표시 삭제까지 묶음 안에서 끝내고 건수만 돌려준다.
     */
    private fun sendAppUpdatePushPage(
        page: List<AppUpdatePushTargetResult>,
        request: SendAppUpdatePushRequest,
    ): AppUpdatePushResponse {
        val notificationIds = page.map {
            NotificationId.createAppUpdateId(it.userId, request.platform, request.appVersion)
        }
        val markedNotificationIds =
            pushDeduplicationRepository.createSentMarks(notificationIds, DEDUPLICATION_TIME_TO_LIVE)

        val messagesByToken = page.zip(notificationIds).mapNotNull { (target, notificationId) ->
            // 이미 받은 사용자는 건너뛴다
            if (notificationId !in markedNotificationIds) {
                return@mapNotNull null
            }

            val language = target.language ?: Language.ORIGINAL_LANGUAGE

            target.token to PushMessage(
                userId = target.userId,
                type = PushType.APP_UPDATE,
                notificationId = notificationId,
                title = messageSource.getMessage("push.appUpdate.title", language),
                body = messageSource.getMessage("push.appUpdate.body", language),
                appLink = appLinkProperties.appUpdate,
            )
        }.toMap()

        val result = notificationService.sendPushes(messagesByToken)

        notificationService.deleteInvalidDeviceTokens(result.invalidTokens)
        pushDeduplicationRepository.deleteSentMarks(result.getRetryableNotificationIds(messagesByToken))

        return AppUpdatePushResponse(
            targetCount = page.size,
            sentCount = messagesByToken.size,
            successCount = result.successCount,
            invalidCount = result.invalidTokens.size,
            retryableCount = result.retryableTokens.size,
        )
    }
}
