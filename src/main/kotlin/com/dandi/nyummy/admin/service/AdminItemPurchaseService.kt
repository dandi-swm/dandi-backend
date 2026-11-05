package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminItemPurchaseLineResponse
import com.dandi.nyummy.admin.dto.AdminItemPurchaseProductResponse
import com.dandi.nyummy.admin.dto.AdminItemPurchasesResponse
import com.dandi.nyummy.admin.mapper.toAdminItemPurchaseLineResponse
import com.dandi.nyummy.admin.mapper.toAdminItemPurchaseProductResponse
import com.dandi.nyummy.admin.mapper.toAdminItemPurchaseRefundResponse
import com.dandi.nyummy.admin.mapper.toAdminItemPurchaseResponse
import com.dandi.nyummy.item.repository.ItemRepository
import com.dandi.nyummy.room.entity.OwnedRoomItem
import com.dandi.nyummy.room.repository.OwnedRoomItemRepository
import com.dandi.nyummy.shop.entity.ItemPurchase
import com.dandi.nyummy.shop.entity.ItemPurchaseLine
import com.dandi.nyummy.shop.enum.ItemPurchaseLineStatus
import com.dandi.nyummy.shop.repository.ItemPurchaseLineRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRefundLineRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRefundRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@ConditionalOnAdminEnabled
class AdminItemPurchaseService(
    private val itemPurchaseRepository: ItemPurchaseRepository,
    private val itemPurchaseProductRepository: ItemPurchaseProductRepository,
    private val itemPurchaseLineRepository: ItemPurchaseLineRepository,
    private val itemPurchaseRefundRepository: ItemPurchaseRefundRepository,
    private val itemPurchaseRefundLineRepository: ItemPurchaseRefundLineRepository,
    private val ownedRoomItemRepository: OwnedRoomItemRepository,
    private val productRepository: ProductRepository,
    private val itemRepository: ItemRepository,
) {

    /**
     * 사용자의 구매 내역을 최신순으로 조회한다. 구매가 없거나 없는 사용자면 빈 목록이다.
     *
     * 구매 상품, 구성품, 환불, 보유 아이템을 묶음 단위로 한 번씩 읽어 구매 수만큼 쿼리가 늘지 않게 한다.
     * 한 트랜잭션에서 읽어, 그사이 환불이 끝나도 구성품 상태와 환불 기록이 어긋나지 않는다.
     */
    @Transactional(readOnly = true)
    fun getItemPurchases(userId: Long, cursor: Long?, size: Int): AdminItemPurchasesResponse {
        val itemPurchases = itemPurchaseRepository.findByUserIdAndIdLessThanOrderByIdDesc(
            userId = userId,
            cursor = cursor ?: Long.MAX_VALUE,
            pageable = PageRequest.of(0, size),
        )
        val productsByItemPurchaseId = getItemPurchaseProductResponses(itemPurchases.content)

        return AdminItemPurchasesResponse(
            itemPurchases = itemPurchases.content.map {
                it.toAdminItemPurchaseResponse(productsByItemPurchaseId[it.id].orEmpty())
            },
            nextCursor = if (itemPurchases.hasNext()) itemPurchases.content.last().id else null,
            hasNext = itemPurchases.hasNext(),
        )
    }

    // 구매 id별 구매 상품 응답. 묶음에 구매가 없으면 쿼리하지 않는다.
    private fun getItemPurchaseProductResponses(
        itemPurchases: List<ItemPurchase>,
    ): Map<Long, List<AdminItemPurchaseProductResponse>> {
        if (itemPurchases.isEmpty()) return emptyMap()

        val products = itemPurchaseProductRepository.findByItemPurchaseIdInOrderByIdAsc(itemPurchases.map { it.id })
        val itemPurchaseProductIds = products.map { it.id }
        val linesByProductId = getItemPurchaseLineResponses(itemPurchaseProductIds)
        val refundsByProductId = itemPurchaseRefundRepository
            .findByItemPurchaseProductIdInOrderByIdAsc(itemPurchaseProductIds)
            .groupBy({ it.itemPurchaseProductId }, { it.toAdminItemPurchaseRefundResponse() })
        val productNames = productRepository.findAllById(products.map { it.productId })
            .associate { it.id to it.name }

        return products.groupBy({ it.itemPurchaseId }) {
            it.toAdminItemPurchaseProductResponse(
                productName = productNames.getValue(it.productId),
                lines = linesByProductId[it.id].orEmpty(),
                refunds = refundsByProductId[it.id].orEmpty(),
            )
        }
    }

    // 구매 상품 id별 구성품 응답
    private fun getItemPurchaseLineResponses(
        itemPurchaseProductIds: List<Long>,
    ): Map<Long, List<AdminItemPurchaseLineResponse>> {
        val lines = itemPurchaseLineRepository.findByItemPurchaseProductIdInOrderByIdAsc(itemPurchaseProductIds)
        val lineIds = lines.map { it.id }
        val refundedLineIds = itemPurchaseRefundLineRepository.findByItemPurchaseLineIdIn(lineIds)
            .map { it.itemPurchaseLineId }
            .toSet()
        val ownedRoomItemsByLineId = ownedRoomItemRepository.findByItemPurchaseLineIdIn(lineIds)
            .associateBy { it.itemPurchaseLineId }
        val itemNames = itemRepository.findAllById(lines.map { it.itemId })
            .associate { it.id to it.name }

        return lines.groupBy({ it.itemPurchaseProductId }) { line ->
            val ownedRoomItem = ownedRoomItemsByLineId[line.id]

            line.toAdminItemPurchaseLineResponse(
                itemName = itemNames.getValue(line.itemId),
                status = getItemPurchaseLineStatus(line, refundedLineIds, ownedRoomItem),
                isEquipped = ownedRoomItem?.isEquipped == true,
            )
        }
    }

    private fun getItemPurchaseLineStatus(
        line: ItemPurchaseLine,
        refundedLineIds: Set<Long>,
        ownedRoomItem: OwnedRoomItem?,
    ): ItemPurchaseLineStatus = when {
        line.id in refundedLineIds -> ItemPurchaseLineStatus.REFUNDED
        ownedRoomItem != null -> ItemPurchaseLineStatus.CLAIMED
        else -> ItemPurchaseLineStatus.NOT_CLAIMED
    }
}
