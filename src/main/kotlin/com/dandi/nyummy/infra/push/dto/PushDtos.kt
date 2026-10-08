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
    operator fun plus(other: PushResult): PushResult = PushResult(
        successCount = successCount + other.successCount,
        invalidTokens = invalidTokens + other.invalidTokens,
        retryableTokens = retryableTokens + other.retryableTokens,
    )

    companion object {
        val EMPTY = PushResult(successCount = 0, invalidTokens = emptyList(), retryableTokens = emptyList())
    }
}
