package com.dandi.nyummy.infra.push

import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushResult

interface PushClient {
    fun sendPushes(messagesByToken: Map<String, PushMessage>): PushResult
}
