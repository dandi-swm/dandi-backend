package com.dandi.nyummy.notification.entity

import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.notification.enum.DevicePlatform
import com.dandi.nyummy.notification.enum.PushOutcome
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 푸시 전송 내역. FCM에 보낸 시도마다 한 행이고, 추가만 하고 수정하지 않는다.
 *
 * 일시 장애로 재시도하면 같은 [notificationId]의 행이 또 생긴다.
 * [platform]은 기록 시점에 기기 토큰을 찾지 못하면 null이다.
 */
@Entity
@Table(name = "push_histories")
class PushHistory(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "notification_id", nullable = false, length = 100)
    val notificationId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    val category: PushType,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", length = 20)
    val platform: DevicePlatform?,

    @Column(name = "title", nullable = false, length = 255)
    val title: String,

    @Column(name = "body", nullable = false, length = 255)
    val body: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    val outcome: PushOutcome,

    @Column(name = "sent_at", nullable = false)
    val sentAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L
}
