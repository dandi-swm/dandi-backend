@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.mail.dto

import com.dandi.nyummy.mail.enum.MailCategory
import java.time.Instant

data class MailsResponse(val mails: List<MailSummaryResponse>, val nextCursor: Long?, val hasNext: Boolean)

/**
 * 우편 목록의 한 건. 이미지 URL은 내려주지 않고, 앱이 [category]로 경로를 고른 뒤 [imageId]를 붙여 CDN URL을 조립한다.
 *
 * @property imageId ITEM은 대표 아이템 id(`items/{imageId}.png`), REWARD_BOX는 상자 번호(`reward-boxes/{imageId}.png`),
 * COIN은 코인 이미지 번호(`coins/{imageId}.png`)
 */
data class MailSummaryResponse(
    val mailId: Long,
    val category: MailCategory,
    val title: String,
    val content: String,
    val imageId: Long?,
    val isClaimed: Boolean,
    val expiresAt: Instant?,
    val createdAt: Instant,
)

data class RewardResponse(
    val coin: RewardCoinResponse?,
    val items: List<RewardItemResponse>?,
    val rewardBox: RewardBoxResponse?,
)

data class TotalRewardResponse(val coin: RewardCoinResponse?, val items: List<RewardItemResponse>?)

/**
 * 받은 보상의 이미지는 목록과 같이 앱이 [imageId]로 조립한다.
 * 코인은 `coins/{imageId}.png`, 아이템은 `items/{imageId}.png`, 상자는 `reward-boxes/{imageId}.png`다.
 */
data class RewardCoinResponse(val quantity: Int, val imageId: Long)

/**
 * @property imageId 아이템 이미지 번호. 지금은 [itemId]와 같지만, 보상 객체마다 같은 규칙으로 조립하도록 따로 둔다.
 */
data class RewardItemResponse(val itemId: Long, val name: String, val imageId: Long, val quantity: Int)

data class RewardBoxResponse(val imageId: Long, val animationUrl: String?)
