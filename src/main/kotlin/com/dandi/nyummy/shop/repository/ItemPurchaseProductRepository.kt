package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.dto.PurchasedProductCountResult
import com.dandi.nyummy.shop.entity.ItemPurchaseProduct
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ItemPurchaseProductRepository : JpaRepository<ItemPurchaseProduct, Long> {
    fun findByItemPurchaseId(itemPurchaseId: Long): List<ItemPurchaseProduct>

    /** 사용자가 상품마다 산 개수. 한 번도 사지 않은 상품은 결과에 없다. */
    @Query(
        """
        select new com.dandi.nyummy.shop.dto.PurchasedProductCountResult(ipp.productId, count(ipp))
        from ItemPurchaseProduct as ipp
            join ItemPurchase as ip on ip.id = ipp.itemPurchaseId
        where ip.userId = :userId
            and ipp.productId in :productIds
        group by ipp.productId
    """,
    )
    fun findPurchasedProductCounts(
        @Param("userId") userId: Long,
        @Param("productIds") productIds: Collection<Long>,
    ): List<PurchasedProductCountResult>
}
