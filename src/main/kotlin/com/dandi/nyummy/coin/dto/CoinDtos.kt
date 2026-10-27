@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.coin.dto

import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import java.time.Instant

data class CoinHistoriesResponse(
    val coinHistories: List<CoinHistoryResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

/**
 * @property title 화면에 보일 문구. 서버가 category와 출처로 만든다
 * @property quantity 변동한 코인. 부호가 있다(+ 획득, − 사용)
 * @property balanceAfter 변동 직후 잔액
 */
data class CoinHistoryResponse(
    val coinHistoryId: Long,
    val category: CoinHistoryCategory,
    val title: String,
    val quantity: Int,
    val balanceAfter: Int,
    val createdAt: Instant,
)
