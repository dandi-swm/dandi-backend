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
        val messages = slot<List<Message>>()
        val response = mockk<BatchResponse> {
            every { responses } returns emptyList()
            every { successCount } returns 1
        }
        every { firebaseMessaging.sendEach(capture(messages), false) } returns response

        pushClient.sendPushes(mapOf("token" to createPushMessage()))

        // Message에 공개 게터가 없어 FCM으로 나가는 JSON 그대로 확인한다
        val jsonFactory = GsonFactory.getDefaultInstance()
        val json = jsonFactory.fromString(jsonFactory.toString(messages.captured.single()), GenericJson::class.java)
        val android = json["android"] as Map<*, *>

        assertEquals("high", android["priority"])
    }

    private fun createPushMessage() = PushMessage(
        userId = 1L,
        type = PushType.MEAL_REMINDER,
        notificationId = "meal-reminder:1:2026-11-03:LUNCH",
        title = "집사, 점심은 먹었어?",
        body = "사진 한 장만 찍어줘!",
        appLink = "nyummy://home",
    )
}
