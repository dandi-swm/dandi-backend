package com.dandi.nyummy.mail.mapper

import com.dandi.nyummy.mail.dto.MailItemResult
import com.dandi.nyummy.mail.dto.MailSummaryResponse
import com.dandi.nyummy.mail.dto.RewardBoxResponse
import com.dandi.nyummy.mail.dto.RewardCoinResponse
import com.dandi.nyummy.mail.dto.RewardItemResponse
import com.dandi.nyummy.mail.dto.RewardResponse
import com.dandi.nyummy.mail.dto.TotalRewardResponse
import com.dandi.nyummy.mail.entity.Mail
import com.dandi.nyummy.mail.enum.MailCategory

// 코인 이미지는 지금 하나뿐이다. 결제에서 코인 이미지가 여럿이 되면 우편에 저장한 값으로 바꾼다.
private const val COIN_IMAGE_ID = 1L

fun Mail.toMailSummaryResponse(): MailSummaryResponse = MailSummaryResponse(
    mailId = this.id,
    category = this.category,
    title = this.title,
    content = this.content,
    imageId = when (this.category) {
        MailCategory.ITEM -> this.representativeItemId
        MailCategory.REWARD_BOX -> this.rewardBoxCategory?.id?.toLong()
        MailCategory.COIN -> COIN_IMAGE_ID
    },
    isClaimed = this.isClaimed,
    expiresAt = this.expiresAt,
    createdAt = this.createdAt,
)

/**
 * @param items 우편에서 지급한 아이템. 없으면 null이다.
 */
fun Mail.toRewardResponse(items: List<RewardItemResponse>?): RewardResponse = RewardResponse(
    coin = this.coinQuantity?.let { RewardCoinResponse(quantity = it, imageId = COIN_IMAGE_ID) },
    items = items,
    rewardBox = this.rewardBoxCategory?.let { RewardBoxResponse(imageId = it.id.toLong(), animationUrl = null) },
)

/**
 * 여러 우편의 코인을 더해 합계로 준다. 코인이 든 우편이 없으면 coin은 null이다.
 *
 * @param items 우편들에서 지급한 아이템의 합계. 없으면 null이다.
 */
fun List<Mail>.toTotalRewardResponse(items: List<RewardItemResponse>?): TotalRewardResponse = TotalRewardResponse(
    coin = this.mapNotNull { it.coinQuantity }
        .takeIf { it.isNotEmpty() }
        ?.let { RewardCoinResponse(quantity = it.sum(), imageId = COIN_IMAGE_ID) },
    items = items,
)

/**
 * 같은 아이템(itemId)을 하나로 합쳐 개수를 더한다. 순서는 각 아이템이 처음 나온 순서(우편에 넣은 순서)이고,
 * 아이템이 없으면 null이다. 아이템 이미지 번호는 지금 상품 id와 같다.
 *
 * @param names itemId별로 요청 언어로 고른 아이템 이름
 */
fun List<MailItemResult>.toRewardItemResponses(names: Map<Long, String>): List<RewardItemResponse>? = this
    .groupBy { it.itemId }
    .map { (itemId, mailItems) ->
        RewardItemResponse(imageId = itemId, name = names.getValue(itemId), quantity = mailItems.sumOf { it.quantity })
    }
    .ifEmpty { null }
