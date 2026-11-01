package com.dandi.nyummy.mail.repository

import com.dandi.nyummy.mail.dto.MailItemResult
import com.dandi.nyummy.mail.entity.MailItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface MailItemRepository : JpaRepository<MailItem, Long> {

    /**
     * 우편들에 든 아이템을 이름과 함께 조회한다. 우편 id 오름차순, 같은 우편 안에서는 넣은 순서(id 오름차순)다.
     */
    @Query(
        """
        select new com.dandi.nyummy.mail.dto.MailItemResult(mi.mailId, mi.itemId, i.name, mi.quantity, mi.itemPurchaseLineId)
        from MailItem as mi
            join Item as i on i.id = mi.itemId
        where mi.mailId in :mailIds
        order by mi.mailId asc, mi.id asc
    """,
    )
    fun findMailItemsByMailIds(@Param("mailIds") mailIds: Collection<Long>): List<MailItemResult>

    /**
     * 샀지만 우편을 아직 받지 않은 아이템 중 [itemIds]에 든 것을 조회한다.
     * 구매 우편은 만료되지 않고, 안 받은 우편은 삭제할 수 없으므로 안 받은 조건만 본다.
     */
    @Query(
        """
        select mi.itemId
        from Mail as m
            join MailItem as mi on mi.mailId = m.id
        where m.userId = :userId
            and m.claimedAt is null
            and mi.itemPurchaseLineId is not null
            and mi.itemId in :itemIds
    """,
    )
    fun findUnclaimedPurchasedItemIds(
        @Param("userId") userId: Long,
        @Param("itemIds") itemIds: Collection<Long>,
    ): List<Long>
}
