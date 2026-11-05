package com.dandi.nyummy.infra.push

import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.google.api.client.json.GenericJson
import com.google.api.client.json.gson.GsonFactory
import com.google.firebase.messaging.BatchResponse
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class FcmPushClientTest {

    private val firebaseMessaging = mockk<FirebaseMessaging>()

    private val pushClient = FcmPushClient(
        firebaseMessaging = firebaseMessaging,
        fcmProperties = FcmProperties(credentialsBase64 = "", dryRun = false),
        clock = Clock.fixed(Instant.parse("2026-11-03T03:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `Android 우선순위를 high로 보낸다`() {
        val json = sendPushAndCaptureJson(createPushMessage())
        val android = json["android"] as Map<*, *>

        assertEquals("high", android["priority"])
    }

    @Test
    fun `iOS 알림에 기본 소리와 즉시 전달 우선순위를 넣는다`() {
        val json = sendPushAndCaptureJson(createPushMessage())
        val apns = json["apns"] as Map<*, *>
        val headers = apns["headers"] as Map<*, *>
        val payload = apns["payload"] as Map<*, *>
        val aps = payload["aps"] as Map<*, *>

        assertEquals("10", headers["apns-priority"])
        assertEquals("default", aps["sound"])
    }

    @Test
    fun `알림 종류를 FCM 분석 라벨로 보낸다`() {
        val json = sendPushAndCaptureJson(createPushMessage(type = PushType.RETENTION))
        val fcmOptions = json["fcm_options"] as Map<*, *>

        assertEquals("RETENTION", fcmOptions["analytics_label"])
    }

    /** Message에 공개 게터가 없어 FCM으로 나가는 JSON 그대로 확인한다 */
    private fun sendPushAndCaptureJson(message: PushMessage): GenericJson {
        val messages = slot<List<Message>>()
        val response = mockk<BatchResponse> {
            every { responses } returns emptyList()
            every { successCount } returns 1
        }
        every { firebaseMessaging.sendEach(capture(messages), false) } returns response

        pushClient.sendPushes(mapOf("token" to message))

        val jsonFactory = GsonFactory.getDefaultInstance()
        return jsonFactory.fromString(jsonFactory.toString(messages.captured.single()), GenericJson::class.java)
    }

    private fun createPushMessage(type: PushType = PushType.MEAL_REMINDER) = PushMessage(
        userId = 1L,
        type = type,
        notificationId = "meal-reminder:1:2026-11-03:LUNCH",
        title = "집사, 점심은 먹었어?",
        body = "사진 한 장만 찍어줘!",
        appLink = "nyummy://home",
    )
}
