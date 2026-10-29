package com.dandi.nyummy.room.repository

import com.dandi.nyummy.room.dto.EquippedRoomItemResult
import com.dandi.nyummy.room.dto.OwnedRoomItemCountResult
import com.dandi.nyummy.room.dto.OwnedRoomItemResult
import com.dandi.nyummy.room.entity.OwnedRoomItem
import com.dandi.nyummy.room.enum.RoomItemCategory
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface OwnedRoomItemRepository : JpaRepository<OwnedRoomItem, Long> {

    /**
     * 사용자의 보유 아이템 하나를 조회한다. 삭제된 아이템도 포함한다.
     * 목록 커서의 정렬 위치(장착 여부·id)를 알아내는 데 쓴다.
     */
    fun findOwnedRoomItemByIdAndUserId(id: Long, userId: Long): OwnedRoomItem?

    /**
     * 사용자의 삭제되지 않은 보유 아이템을 모두 조회한다. 꾸민 방 저장에서 장착·해제할 대상을 고르는 데 쓴다.
     */
    fun findOwnedRoomItemsByUserIdAndDeletedAtIsNull(userId: Long): List<OwnedRoomItem>

    /**
     * 한 카테고리의 보유 아이템을 장착한 것 먼저, 같은 장착 여부 안에서는 최신순(id 내림차순)으로 조회한다.
     *
     * 정렬이 두 값이라 커서도 두 값으로 비교한다. 커서보다 뒤 순서는 "커서가 장착이면 미장착 전부"와
     * "커서와 장착 여부가 같고 id가 더 작은 것"이다. 첫 묶음은 (장착, Long.MAX_VALUE)를 커서로 넘기면 전부가 된다.
     * boolean 크기 비교 대신 조건을 풀어 쓴다.
     *
     * 위치는 커서 조건이 정하므로 [pageable]은 page 0으로 개수만 정한다.
     */
    @Query(
        """
        select new com.dandi.nyummy.room.dto.OwnedRoomItemResult(o.id, i.name, o.itemId, o.isEquipped)
        from OwnedRoomItem as o
            join RoomItem as r on r.itemId = o.itemId
            join Item as i on i.id = o.itemId
        where o.userId = :userId
            and o.deletedAt is null
            and r.category = :category
            and (
                (:cursorIsEquipped = true and o.isEquipped = false)
                or (o.isEquipped = :cursorIsEquipped and o.id < :cursorId)
            )
        order by o.isEquipped desc, o.id desc
    """,
    )
    fun findOwnedRoomItemsByUserIdBeforeCursor(
        @Param("userId") userId: Long,
        @Param("category") category: RoomItemCategory,
        @Param("cursorIsEquipped") cursorIsEquipped: Boolean,
        @Param("cursorId") cursorId: Long,
        pageable: Pageable,
    ): Slice<OwnedRoomItemResult>

    /**
     * 사용자가 장착한 아이템을 그리는 순서(zIndex 오름차순)로 조회한다. 삭제된 아이템은 제외한다.
     */
    @Query(
        """
        select new com.dandi.nyummy.room.dto.EquippedRoomItemResult(
            o.id, r.category, o.itemId, o.x, o.y, o.zIndex, o.scale
        )
        from OwnedRoomItem as o
            join RoomItem as r on r.itemId = o.itemId
        where o.userId = :userId
            and o.deletedAt is null
            and o.isEquipped = true
        order by o.zIndex asc, o.id asc
    """,
    )
    fun findEquippedRoomItemsByUserId(@Param("userId") userId: Long): List<EquippedRoomItemResult>

    @Query(
        """
            select new com.dandi.nyummy.room.dto.OwnedRoomItemCountResult(o.itemId, count(o))
            from OwnedRoomItem as o
            where o.userId = :userId
                and o.itemId in :itemIds
                and o.deletedAt is null
            group by o.itemId
        """,
    )
    fun findOwnedRoomItemCounts(
        @Param("userId") userId: Long,
        @Param("itemIds") itemIds: List<Long>,
    ): List<OwnedRoomItemCountResult>
}
