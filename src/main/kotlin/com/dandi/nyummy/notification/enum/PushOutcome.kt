package com.dandi.nyummy.notification.enum

/**
 * FCM에 보낸 결과. FCM이 접수했는지까지만 뜻하고, 기기에 도착했는지는 알 수 없다.
 */
enum class PushOutcome {
    SUCCESS,
    INVALID_TOKEN,
    RETRYABLE,
}
