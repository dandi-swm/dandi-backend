package com.dandi.nyummy.infra.push.dto

enum class PushType {
    MEAL_REMINDER,
    RETENTION,
    ANALYSIS_COMPLETED,
    ANALYSIS_FAILED,
}

data class PushMessage(
    val type: PushType,
    val notificationId: String,
    val title: String,
    val body: String,
    val catStatus: String? = null,
    val appLink: String,
    val imageUrl: String? = null,
)

data class PushResult(
    val successCount: Int,
    val invalidTokens: List<String>, // 죽은 토큰 -> DB에서 삭제
    val retryableTokens: List<String>, // 일시 장애
) {
    /**
     * 일시 장애로 못 받은 메시지의 [PushMessage.notificationId] 목록.
     *
     * 발송 결과는 토큰으로 돌아오므로, 보낸 메시지와 짝지어 식별자를 되찾는다. 호출부는 이 값으로
     * 중복 발송 표시를 지워 재시도가 그 사용자를 다시 보낼 수 있게 한다.
     */
    fun getRetryableNotificationIds(messagesByToken: Map<String, PushMessage>): List<String> =
        retryableTokens.mapNotNull { messagesByToken[it]?.notificationId }

    operator fun plus(other: PushResult): PushResult = PushResult(
        successCount = successCount + other.successCount,
        invalidTokens = invalidTokens + other.invalidTokens,
        retryableTokens = retryableTokens + other.retryableTokens,
    )

    companion object {
        val EMPTY = PushResult(successCount = 0, invalidTokens = emptyList(), retryableTokens = emptyList())
    }
}
