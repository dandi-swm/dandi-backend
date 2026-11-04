package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.CreateItemPurchaseRefundRequest
import com.dandi.nyummy.admin.dto.ItemPurchaseRefundPreviewResponse
import com.dandi.nyummy.admin.dto.ItemPurchaseRefundResponse
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import org.springframework.stereotype.Service

/**
 * 상점 환불. 사용자 id를 트랜잭션 밖에서 읽어 [AdminItemPurchaseRefundTransactionService]에 넘긴다.
 *
 * 환불 트랜잭션은 그 사용자의 코인 행 잠금이 첫 쿼리여야 하는데, 잠글 사용자를 알려면 먼저 읽어야 한다.
 * 같은 트랜잭션에서 읽으면 REPEATABLE READ의 스냅샷이 잠금 전에 생겨, 그사이 받은 우편의 보유 아이템을 보지 못한다.
 */
@Service
@ConditionalOnAdminEnabled
class AdminItemPurchaseRefundService(
    private val itemPurchaseProductRepository: ItemPurchaseProductRepository,
    private val adminItemPurchaseRefundTransactionService: AdminItemPurchaseRefundTransactionService,
) {

    /**
     * 환불하면 돌려줄 코인과 정책에 어긋나는 이유를 미리 계산한다. 아무것도 바꾸지 않는다.
     *
     * @throws BusinessException [AdminErrorCode.ITEM_PURCHASE_PRODUCT_NOT_FOUND] 구매 상품이 없는 경우
     * @throws BusinessException [AdminErrorCode.ITEM_PURCHASE_LINE_NOT_FOUND] 이 구매 상품의 구성품이 아닌 것이 있는 경우
     * @throws BusinessException [AdminErrorCode.ALREADY_REFUNDED] 이미 환불한 구성품이 있는 경우
     */
    fun getRefundPreview(
        itemPurchaseProductId: Long,
        itemPurchaseLineIds: List<Long>,
    ): ItemPurchaseRefundPreviewResponse =
        adminItemPurchaseRefundTransactionService.getRefundPreview(itemPurchaseProductId, itemPurchaseLineIds)

    /**
     * 구성품을 환불한다. 아이템을 회수하고 코인을 돌려주며, 돌려줄 코인이 0이면 코인 내역을 남기지 않는다.
     *
     * @throws BusinessException [AdminErrorCode.ITEM_PURCHASE_PRODUCT_NOT_FOUND] 구매 상품이 없는 경우
     * @throws BusinessException [AdminErrorCode.ITEM_PURCHASE_LINE_NOT_FOUND] 이 구매 상품의 구성품이 아닌 것이 있는 경우
     * @throws BusinessException [AdminErrorCode.ALREADY_REFUNDED] 이미 환불한 구성품이 있는 경우
     * @throws BusinessException [AdminErrorCode.REFUND_POLICY_EXCEPTION_REQUIRED] 정책에 어긋나는데 예외로 표시하지 않은 경우
     */
    fun createRefund(
        adminId: Long,
        itemPurchaseProductId: Long,
        request: CreateItemPurchaseRefundRequest,
    ): ItemPurchaseRefundResponse {
        val userId = itemPurchaseProductRepository.findUserIdById(itemPurchaseProductId)
            ?: throw BusinessException(AdminErrorCode.ITEM_PURCHASE_PRODUCT_NOT_FOUND)

        return adminItemPurchaseRefundTransactionService.createRefund(
            userId,
            adminId,
            itemPurchaseProductId,
            request,
        )
    }
}
