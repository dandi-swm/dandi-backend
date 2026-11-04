package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.CreateItemPurchaseRefundRequest
import com.dandi.nyummy.admin.dto.ItemPurchaseRefundCalculationResult
import com.dandi.nyummy.admin.dto.ItemPurchaseRefundLinePreviewResponse
import com.dandi.nyummy.admin.dto.ItemPurchaseRefundPreviewResponse
import com.dandi.nyummy.admin.dto.ItemPurchaseRefundResponse
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.mail.repository.MailItemRepository
import com.dandi.nyummy.mail.repository.MailRepository
import com.dandi.nyummy.room.entity.OwnedRoomItem
import com.dandi.nyummy.room.repository.OwnedRoomItemRepository
import com.dandi.nyummy.shop.calculator.calculateRefundCoinQuantity
import com.dandi.nyummy.shop.config.ShopProperties
import com.dandi.nyummy.shop.entity.ItemPurchaseProduct
import com.dandi.nyummy.shop.entity.ItemPurchaseRefund
import com.dandi.nyummy.shop.entity.ItemPurchaseRefundLine
import com.dandi.nyummy.shop.enum.RefundPolicyExceptionCategory
import com.dandi.nyummy.shop.repository.ItemPurchaseLineRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRefundLineRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRefundRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 환불 계산과 실행 트랜잭션. 코인 행 잠금 앞에서 사용자 id를 읽는 일은 [AdminItemPurchaseRefundService]가 한다.
 */
@Service
@ConditionalOnAdminEnabled
class AdminItemPurchaseRefundTransactionService(
    private val coinService: CoinService,
    private val shopProperties: ShopProperties,
    private val itemPurchaseRepository: ItemPurchaseRepository,
    private val itemPurchaseProductRepository: ItemPurchaseProductRepository,
    private val itemPurchaseLineRepository: ItemPurchaseLineRepository,
    private val itemPurchaseRefundRepository: ItemPurchaseRefundRepository,
    private val itemPurchaseRefundLineRepository: ItemPurchaseRefundLineRepository,
    private val ownedRoomItemRepository: OwnedRoomItemRepository,
    private val mailRepository: MailRepository,
    private val mailItemRepository: MailItemRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun getRefundPreview(
        itemPurchaseProductId: Long,
        itemPurchaseLineIds: List<Long>,
    ): ItemPurchaseRefundPreviewResponse {
        val calculation = calculateRefund(getItemPurchaseProduct(itemPurchaseProductId), itemPurchaseLineIds.toSet())

        return ItemPurchaseRefundPreviewResponse(
            coinQuantity = calculation.coinQuantity,
            policyExceptions = calculation.policyExceptions,
            lines = calculation.lines,
        )
    }

    /**
     * 코인 행 잠금이 첫 쿼리다. 우편 받기·구매·마이룸 쓰기가 같은 잠금을 쓰므로, 환불은 그 사이에 끼지 않고 최신 행을 본다.
     * 같은 구성품을 동시에 환불해도 뒤 요청은 앞 요청의 기록을 보고 거부되고, 유니크 제약이 한 번 더 막는다.
     */
    @Transactional
    fun createRefund(
        userId: Long,
        adminId: Long,
        itemPurchaseProductId: Long,
        request: CreateItemPurchaseRefundRequest,
    ): ItemPurchaseRefundResponse {
        val coin = coinService.lockCoin(userId)
        val itemPurchaseLineIds = request.itemPurchaseLineIds.toSet()
        val calculation = calculateRefund(getItemPurchaseProduct(itemPurchaseProductId), itemPurchaseLineIds)
        if (calculation.policyExceptions.isNotEmpty() && !request.isPolicyException) {
            throw BusinessException(AdminErrorCode.REFUND_POLICY_EXCEPTION_REQUIRED)
        }

        val refund = itemPurchaseRefundRepository.save(
            ItemPurchaseRefund(itemPurchaseProductId, adminId, calculation.coinQuantity, request.reason.trim()),
        )
        itemPurchaseRefundLineRepository.saveAll(itemPurchaseLineIds.map { ItemPurchaseRefundLine(refund.id, it) })
        revokeItems(itemPurchaseLineIds, calculation.ownedRoomItems, Instant.now(clock))

        val balance = if (calculation.coinQuantity > 0) {
            coinService.increaseBalance(
                userId,
                calculation.coinQuantity,
                CoinHistoryCategory.ITEM_PURCHASE_REFUND,
                refund.id,
            )
        } else {
            coin.balance
        }

        return ItemPurchaseRefundResponse(refund.id, calculation.coinQuantity, balance)
    }

    private fun getItemPurchaseProduct(itemPurchaseProductId: Long): ItemPurchaseProduct =
        itemPurchaseProductRepository.findByIdOrNull(itemPurchaseProductId)
            ?: throw BusinessException(AdminErrorCode.ITEM_PURCHASE_PRODUCT_NOT_FOUND)

    private fun calculateRefund(
        itemPurchaseProduct: ItemPurchaseProduct,
        itemPurchaseLineIds: Set<Long>,
    ): ItemPurchaseRefundCalculationResult {
        val lines = itemPurchaseLineRepository.findByItemPurchaseProductId(itemPurchaseProduct.id)
        val productLineIds = lines.map { it.id }.toSet()
        if (!productLineIds.containsAll(itemPurchaseLineIds)) {
            throw BusinessException(AdminErrorCode.ITEM_PURCHASE_LINE_NOT_FOUND)
        }

        val refundedLineIds = itemPurchaseRefundLineRepository.findByItemPurchaseLineIdIn(productLineIds)
            .map { it.itemPurchaseLineId }
            .toSet()
        if (itemPurchaseLineIds.any { it in refundedLineIds }) {
            throw BusinessException(AdminErrorCode.ALREADY_REFUNDED)
        }

        val coinQuantity = calculateRefundCoinQuantity(
            purchasePrice = itemPurchaseProduct.purchasePrice,
            remainingListPrice = lines
                .filter { it.id !in itemPurchaseLineIds && it.id !in refundedLineIds }
                .sumOf { it.listPrice },
            refundedCoinQuantity = itemPurchaseRefundRepository
                .findByItemPurchaseProductId(itemPurchaseProduct.id)
                .sumOf { it.coinQuantity },
        )
        val ownedRoomItems = ownedRoomItemRepository.findByItemPurchaseLineIdIn(itemPurchaseLineIds)

        val refundLines = createRefundLinePreviews(itemPurchaseProduct, itemPurchaseLineIds, ownedRoomItems)
        val policyExceptions = refundLines.flatMap { it.policyExceptions }.distinct() +
            listOfNotNull(RefundPolicyExceptionCategory.ZERO_COIN.takeIf { coinQuantity == 0 })

        return ItemPurchaseRefundCalculationResult(
            coinQuantity = coinQuantity,
            policyExceptions = policyExceptions,
            lines = refundLines,
            ownedRoomItems = ownedRoomItems,
        )
    }

    // 구매 시각은 구매 상품 하나에 같아 기간 초과는 모든 구성품에 붙고, 장착 이력은 구성품마다 다르다
    private fun createRefundLinePreviews(
        itemPurchaseProduct: ItemPurchaseProduct,
        itemPurchaseLineIds: Set<Long>,
        ownedRoomItems: List<OwnedRoomItem>,
    ): List<ItemPurchaseRefundLinePreviewResponse> {
        val purchasedAt = itemPurchaseRepository.getReferenceById(itemPurchaseProduct.itemPurchaseId).createdAt
        val isPeriodExpired = Instant.now(clock).isAfter(purchasedAt.plus(shopProperties.refundTimeToLive))
        val equippedLineIds = ownedRoomItems
            .filter { it.firstEquippedAt != null }
            .mapNotNull { it.itemPurchaseLineId }
            .toSet()

        return itemPurchaseLineIds.map { itemPurchaseLineId ->
            ItemPurchaseRefundLinePreviewResponse(
                itemPurchaseLineId = itemPurchaseLineId,
                policyExceptions = buildList {
                    if (isPeriodExpired) add(RefundPolicyExceptionCategory.PERIOD_EXPIRED)
                    if (itemPurchaseLineId in equippedLineIds) add(RefundPolicyExceptionCategory.EQUIPPED)
                },
            )
        }
    }

    // 받은 구성품은 보유 아이템을, 받기 전 구성품은 우편 아이템을 회수한다. 우편에 남은 아이템이 없으면 우편도 숨긴다.
    private fun revokeItems(itemPurchaseLineIds: Set<Long>, ownedRoomItems: List<OwnedRoomItem>, now: Instant) {
        ownedRoomItems.forEach { it.revoke(now) }

        val claimedLineIds = ownedRoomItems.mapNotNull { it.itemPurchaseLineId }.toSet()
        val mailItems = mailItemRepository.findByItemPurchaseLineIdIn(itemPurchaseLineIds - claimedLineIds)
        mailItems.forEach { it.revoke(now) }

        val emptyMailIds = mailItems.map { it.mailId }.distinct().filterNot {
            mailItemRepository.existsByMailIdAndRevokedAtIsNull(it)
        }
        mailRepository.findAllById(emptyMailIds).forEach { it.revoke(now) }
    }
}
