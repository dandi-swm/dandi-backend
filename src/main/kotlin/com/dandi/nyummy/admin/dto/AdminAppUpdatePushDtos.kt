@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.admin.dto

import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.notification.enum.DevicePlatform
import jakarta.validation.constraints.Pattern

/**
 * @property platform 새 버전을 출시한 플랫폼. 이 플랫폼 기기에만 보낸다.
 * @property appVersion 출시한 앱 버전. 사용자의 앱 버전과 비교하지 않아 이미 업데이트한 사용자에게도 간다.
 *   같은 출시를 두 번 보내지 않도록 중복 발송 방지 키에만 쓴다.
 */
data class SendAppUpdatePushRequest(
    val platform: DevicePlatform,

    // 중복 발송 방지 키에 들어가므로 숫자와 점만 받는다
    @field:Pattern(regexp = "^[0-9]+(\\.[0-9]+){1,3}$", message = "앱 버전은 1.4.0처럼 숫자와 점으로 적어 주세요.")
    val appVersion: String,
)

/**
 * 앱 업데이트 알림 발송 건수. 묶음마다 만들어 더한다.
 *
 * @property targetCount 서비스 알림을 켠 그 플랫폼 사용자 수
 * @property sentCount FCM에 보낸 수. 같은 출시로 이미 받은 사용자는 빠진다.
 * @property successCount FCM이 접수한 수. 기기에 도착했는지는 알 수 없다.
 * @property invalidCount 죽은 토큰이라 지운 수
 * @property retryableCount 일시 장애로 못 보낸 수. 같은 플랫폼·버전으로 다시 보내면 이들에게만 간다.
 */
data class AppUpdatePushResponse(
    val targetCount: Int,
    val sentCount: Int,
    val successCount: Int,
    val invalidCount: Int,
    val retryableCount: Int,
) {
    operator fun plus(other: AppUpdatePushResponse): AppUpdatePushResponse = AppUpdatePushResponse(
        targetCount = targetCount + other.targetCount,
        sentCount = sentCount + other.sentCount,
        successCount = successCount + other.successCount,
        invalidCount = invalidCount + other.invalidCount,
        retryableCount = retryableCount + other.retryableCount,
    )

    companion object {
        val EMPTY = AppUpdatePushResponse(
            targetCount = 0,
            sentCount = 0,
            successCount = 0,
            invalidCount = 0,
            retryableCount = 0,
        )
    }
}

/** @property language 기기 앱 언어. V0.53 전에 등록한 기기 토큰은 null이고, 원본 언어로 보낸다. */
data class AppUpdatePushTargetResult(val userId: Long, val token: String, val language: Language?)
