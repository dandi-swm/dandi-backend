package com.dandi.nyummy.shop.service

import com.dandi.nyummy.shop.dto.CreateItemPurchaseRequest
import com.dandi.nyummy.shop.entity.ItemPurchaseAttempt
import com.dandi.nyummy.shop.enum.ItemPurchaseOutcome
import com.dandi.nyummy.shop.repository.ItemPurchaseAttemptRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * 구매 시도를 기록한다. 성공은 구매 트랜잭션 안에서, 실패는 그 트랜잭션이 롤백된 뒤 따로 기록한다.
 */
@Service
class ItemPurchaseAttemptService(
    private val itemPurchaseAttemptRepository: ItemPurchaseAttemptRepository,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private const val APP_VERSION_MAX_LENGTH = 20
    }

    /**
     * 앱이 보낸 요청을 그대로 남긴다. 요청 값은 검증 전일 수 있어 합계가 Int 범위를 넘으면 최댓값으로 남긴다.
     * 앱 버전이 컬럼 길이를 넘으면 null로 둔다. 부가 정보 때문에 기록이 실패하지 않게 하기 위함이다.
     *
     * @param itemPurchaseId 만들거나 다시 돌려준 구매. 실패했으면 null이다
     */
    @Transactional
    fun createItemPurchaseAttempt(
        userId: Long,
        idempotencyKey: String,
        request: CreateItemPurchaseRequest,
        appVersion: String?,
        outcome: ItemPurchaseOutcome,
        itemPurchaseId: Long? = null,
    ) {
        itemPurchaseAttemptRepository.save(
            ItemPurchaseAttempt(
                userId = userId,
                requestedProducts = objectMapper.writeValueAsString(request.products),
                requestedTotalPrice = request.products
                    .sumOf { it.purchasePrice.toLong() * it.quantity }
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
                idempotencyKey = idempotencyKey,
                outcome = outcome,
                itemPurchaseId = itemPurchaseId,
                appVersion = appVersion?.takeIf { it.length <= APP_VERSION_MAX_LENGTH },
            ),
        )
    }
}
