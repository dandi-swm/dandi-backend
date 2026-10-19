package com.dandi.nyummy.mail.mapper

import com.dandi.nyummy.mail.dto.MailSummaryResponse
import com.dandi.nyummy.mail.dto.RewardBoxResponse
import com.dandi.nyummy.mail.dto.RewardCoinResponse
import com.dandi.nyummy.mail.dto.RewardResponse
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

// 아이템 지급은 아직 구현 전이라 mail_items는 보지 않는다. 지금은 코인과 상자만 담는다.
fun Mail.toRewardResponse(): RewardResponse = RewardResponse(
    coin = this.coinQuantity?.let { RewardCoinResponse(quantity = it, imageId = COIN_IMAGE_ID) },
    items = null,
    rewardBox = this.rewardBoxCategory?.let { RewardBoxResponse(imageId = it.id.toLong(), animationUrl = null) },
)
