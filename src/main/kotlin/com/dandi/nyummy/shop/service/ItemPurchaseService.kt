package com.dandi.nyummy.shop.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.shop.dto.CreateItemPurchaseRequest
import com.dandi.nyummy.shop.dto.ItemPurchaseResponse
import com.dandi.nyummy.shop.enum.ItemPurchaseOutcome
import org.slf4j.LoggerFactory
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service

/**
 * 상점 구매. 트랜잭션은 [ItemPurchaseTransactionService]가 열고, 여기서는 실패한 시도를 롤백 뒤에 기록한다.
 * 트랜잭션 안에서 기록하면 구매와 함께 롤백되어 남지 않는다.
 */
@Service
class ItemPurchaseService(
    private val itemPurchaseTransactionService: ItemPurchaseTransactionService,
    private val itemPurchaseAttemptService: ItemPurchaseAttemptService,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ItemPurchaseService::class.java)
    }

    /**
     * 장바구니에서 선택한 상품을 코인으로 산다. 거부 사유는 [ItemPurchaseTransactionService.createItemPurchase]를 본다.
     *
     * 잠금을 제시간에 얻지 못하거나 교착으로 롤백되면 앞선 요청의 결과를 알 수 없으므로 처리 중으로 응답한다.
     * 앱은 새 키를 만들지 않고 같은 키로 다시 보내거나 잔액과 장바구니를 다시 조회한다.
     *
     * 거부 사유가 아닌 예외도 [ItemPurchaseOutcome.UNEXPECTED_ERROR]로 기록하고 그대로 던진다. 응답과 로그는 전역 예외 처리가 맡는다.
     *
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 같은 상품을 두 번 보낸 경우
     * @throws BusinessException [ShopErrorCode.PURCHASE_IN_PROGRESS] 코인 행 잠금을 제시간에 얻지 못하거나 교착으로 롤백된 경우
     */
    fun createItemPurchase(
        userId: Long,
        idempotencyKey: String,
        request: CreateItemPurchaseRequest,
        appVersion: String?,
        language: Language,
    ): ItemPurchaseResponse {
        val productIds = request.products.map { it.productId }
        if (productIds.size != productIds.toSet().size) throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE)

        try {
            return itemPurchaseTransactionService.createItemPurchase(
                userId,
                idempotencyKey,
                request,
                appVersion,
                language,
            )
        } catch (e: BusinessException) {
            createFailedAttempt(userId, idempotencyKey, request, appVersion, ItemPurchaseOutcome.from(e.errorCode))
            throw e
        } catch (e: CannotAcquireLockException) {
            logger.warn("구매 잠금을 얻지 못했습니다: userId={}", userId, e)
            createFailedAttempt(userId, idempotencyKey, request, appVersion, ItemPurchaseOutcome.PURCHASE_IN_PROGRESS)
            throw BusinessException(ShopErrorCode.PURCHASE_IN_PROGRESS)
        } catch (e: RuntimeException) {
            createFailedAttempt(userId, idempotencyKey, request, appVersion, ItemPurchaseOutcome.UNEXPECTED_ERROR)
            throw e
        }
    }

    // 기록이 실패해도 사용자에게는 원래 실패를 그대로 응답한다
    private fun createFailedAttempt(
        userId: Long,
        idempotencyKey: String,
        request: CreateItemPurchaseRequest,
        appVersion: String?,
        outcome: ItemPurchaseOutcome,
    ) {
        try {
            itemPurchaseAttemptService.createItemPurchaseAttempt(userId, idempotencyKey, request, appVersion, outcome)
        } catch (e: DataAccessException) {
            logger.error("구매 시도를 기록하지 못했습니다: userId={}, outcome={}", userId, outcome, e)
        }
    }
}
