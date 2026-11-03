package com.dandi.nyummy.coin.mapper

import com.dandi.nyummy.coin.dto.CoinHistoryResponse
import com.dandi.nyummy.coin.entity.CoinHistory

/**
 * @param title 화면에 보일 문구. 종류와 출처로 정하므로 부르는 쪽이 만든다
 */
fun CoinHistory.toCoinHistoryResponse(title: String): CoinHistoryResponse = CoinHistoryResponse(
    coinHistoryId = this.id,
    category = this.category,
    title = title,
    quantity = this.quantity,
    balanceAfter = this.balanceAfter,
    createdAt = this.createdAt,
)
