package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.dto.ItemPurchaseProductNameResult
import com.dandi.nyummy.shop.dto.PurchasedProductCountResult
import com.dandi.nyummy.shop.entity.ItemPurchaseProduct
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ItemPurchaseProductRepository : JpaRepository<ItemPurchaseProduct, Long> {
    fun findByItemPurchaseId(itemPurchaseId: Long): List<ItemPurchaseProduct>

    /** 구매 상품을 산 사용자 id. 구매 상품이 없으면 null이다. */
    @Query(
        """
        select ip.userId
        from ItemPurchaseProduct as ipp
            join ItemPurchase as ip on ip.id = ipp.itemPurchaseId
        where ipp.id = :itemPurchaseProductId
    """,
    )
    fun findUserIdById(@Param("itemPurchaseProductId") itemPurchaseProductId: Long): Long?

    /** 구매들의 구매 상품 이름을 구매 상품 id 순서로 조회한다. 코인 내역 문구를 한 번의 쿼리로 만드는 데 쓴다. */
    @Query(
        """
        select new com.dandi.nyummy.shop.dto.ItemPurchaseProductNameResult(ipp.itemPurchaseId, p.name)
        from ItemPurchaseProduct as ipp
            join Product as p on p.id = ipp.productId
        where ipp.itemPurchaseId in :itemPurchaseIds
        order by ipp.id asc
    """,
    )
    fun findItemPurchaseProductNames(
        @Param("itemPurchaseIds") itemPurchaseIds: Collection<Long>,
    ): List<ItemPurchaseProductNameResult>

    /** [startsAt] 이후 상품마다 팔린 개수. 그동안 팔리지 않은 상품은 결과에 없다. */
    @Query(
        """
        select new com.dandi.nyummy.shop.dto.PurchasedProductCountResult(ipp.productId, count(ipp))
        from ItemPurchaseProduct as ipp
            join ItemPurchase as ip on ip.id = ipp.itemPurchaseId
        where ip.createdAt >= :startsAt
        group by ipp.productId
    """,
    )
    fun findSalesCounts(@Param("startsAt") startsAt: Instant): List<PurchasedProductCountResult>

    /**
     * 사용자가 상품마다 산 개수. 한 번도 사지 않은 상품은 결과에 없다.
     * 구성품을 모두 환불한 구매 상품은 세지 않고, 세트를 일부만 환불했으면 남은 구성품이 있어 센다.
     */
    @Query(
        """
        select new com.dandi.nyummy.shop.dto.PurchasedProductCountResult(ipp.productId, count(ipp))
        from ItemPurchaseProduct as ipp
            join ItemPurchase as ip on ip.id = ipp.itemPurchaseId
        where ip.userId = :userId
            and ipp.productId in :productIds
            and exists (
                select 1
                from ItemPurchaseLine as ipl
                where ipl.itemPurchaseProductId = ipp.id
                    and not exists (
                        select 1
                        from ItemPurchaseRefundLine as iprl
                        where iprl.itemPurchaseLineId = ipl.id
                    )
            )
        group by ipp.productId
    """,
    )
    fun findPurchasedProductCounts(
        @Param("userId") userId: Long,
        @Param("productIds") productIds: Collection<Long>,
    ): List<PurchasedProductCountResult>
}
