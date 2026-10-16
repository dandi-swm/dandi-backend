package com.dandi.nyummy.room.service

import com.dandi.nyummy.room.dto.EquippedRoomItemResponse
import com.dandi.nyummy.room.dto.OwnedRoomItemResponse
import com.dandi.nyummy.room.dto.OwnedRoomItemsResponse
import com.dandi.nyummy.room.dto.RoomItemCategoryResponse
import com.dandi.nyummy.room.dto.RoomResponse
import com.dandi.nyummy.room.dto.UpdateEquippedRoomItemsRequest
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.enum.RoomItemCategory.BACKGROUND
import com.dandi.nyummy.room.enum.RoomItemCategory.BED
import com.dandi.nyummy.room.enum.RoomItemCategory.CARPET
import com.dandi.nyummy.room.enum.RoomItemCategory.ETC
import org.springframework.stereotype.Service

@Service
class RoomService {
    fun getRoom(userId: Long, size: Int): RoomResponse = RoomResponse(
        categories = RoomItemCategory.entries.map { category ->
            val ownedRoomItems = getOwnedRoomItems(userId, category, null, size)
            RoomItemCategoryResponse(
                category = category,
                categoryName = category.description,
                ownedRoomItems = ownedRoomItems.ownedRoomItems,
                nextCursor = ownedRoomItems.nextCursor,
                hasNext = ownedRoomItems.hasNext,
            )
        },
        equippedRoomItems = getEquippedRoomItems(userId),
    )

    fun getOwnedRoomItems(userId: Long, category: RoomItemCategory, cursor: Long?, size: Int): OwnedRoomItemsResponse {
        // 장착한 아이템 먼저, 같은 장착 여부 안에서는 최신순.
        // 커서 아이템보다 뒤 순서인 것만 조회
        val cursorItem = cursor?.let { cursorId -> sampleOwnedRoomItems.find { it.roomItemId == cursorId } }
        val ownedRoomItems = sampleOwnedRoomItems
            .filter { it.category == category }
            .filter { cursorItem == null || ownedRoomItemOrder.compare(it, cursorItem) > 0 }
            .sortedWith(ownedRoomItemOrder)
            .take(size + 1)
        val hasNext = ownedRoomItems.size > size
        val page = ownedRoomItems.take(size)
        return OwnedRoomItemsResponse(
            ownedRoomItems = page.map {
                OwnedRoomItemResponse(
                    roomItemId = it.roomItemId,
                    name = it.name,
                    imageUrl = createImageUrl(it.imageKey),
                    isEquipped = it.isEquipped,
                )
            },
            nextCursor = if (hasNext) page.last().roomItemId else null,
            hasNext = hasNext,
        )
    }

    fun getEquippedRoomItems(userId: Long): List<EquippedRoomItemResponse> = sampleOwnedRoomItems
        .filter { it.isEquipped }
        .sortedBy { it.zIndex }
        .map {
            EquippedRoomItemResponse(
                roomItemId = it.roomItemId,
                category = it.category,
                imageUrl = createImageUrl(it.imageKey),
                x = it.x,
                y = it.y,
                zIndex = it.zIndex,
                scale = it.scale,
            )
        }

    fun updateEquippedRoomItems(userId: Long, request: UpdateEquippedRoomItemsRequest) {
        // 해피케이스: 저장하지 않고 성공만 응답
    }

    fun deleteRoomItem(userId: Long, roomItemId: Long) {
        // 해피케이스: 저장하지 않고 성공만 응답
    }

    private fun createImageUrl(imageKey: String): String = "$SAMPLE_IMAGE_BASE_URL$imageKey"

    private data class SampleOwnedRoomItem(
        val roomItemId: Long,
        val category: RoomItemCategory,
        val name: String,
        val imageKey: String,
        val isEquipped: Boolean,
        val x: Float = 0f,
        val y: Float = 0f,
        val zIndex: Int = 0,
        val scale: Float = 1f,
    )

    companion object {
        private const val SAMPLE_IMAGE_BASE_URL = "https://cdn.nyummy.co.kr/items/"

        private val ownedRoomItemOrder = compareByDescending<SampleOwnedRoomItem> { it.isEquipped }
            .thenByDescending { it.roomItemId }

        private val sampleOwnedRoomItems = listOf(
            // 배경: 3개 → size=2로 조회하면 다음 페이지 확인
            SampleOwnedRoomItem(95, BACKGROUND, "기본 배경", "background/default.png", isEquipped = false),
            SampleOwnedRoomItem(80, BACKGROUND, "밤하늘 배경", "background/night_sky.png", isEquipped = false),
            SampleOwnedRoomItem(108, BACKGROUND, "노을 배경", "background/sunset.png", isEquipped = true),

            // 침대: 장착 1개
            SampleOwnedRoomItem(
                101,
                BED,
                "구름 쿠션",
                "bed/cloud_cushion.png",
                isEquipped = true,
                x = 150f,
                y = 420f,
                zIndex = 4,
            ),

            // 캣휠: 보유 없음

            // 카펫: 장착 1개 + 미장착 1개
            SampleOwnedRoomItem(
                104,
                CARPET,
                "동그라미 카펫",
                "carpet/circle.png",
                isEquipped = true,
                x = 120f,
                y = 480f,
                zIndex = 1,
            ),
            SampleOwnedRoomItem(103, CARPET, "줄무늬 카펫", "carpet/stripe.png", isEquipped = false),

            // 기타: 같은 아이템 3개 구매 → roomItemId는 각각 다름, 2개 장착(좌표 다름) + 1개 미장착
            SampleOwnedRoomItem(
                112, ETC, "털실 공", "etc/yarn_ball.png",
                isEquipped = true, x = 40.5f, y = 520f, zIndex = 2, scale = 0.8f,
            ),
            SampleOwnedRoomItem(
                111, ETC, "털실 공", "etc/yarn_ball.png",
                isEquipped = true, x = 260f, y = 515.5f, zIndex = 3, scale = 1.2f,
            ),
            SampleOwnedRoomItem(110, ETC, "털실 공", "etc/yarn_ball.png", isEquipped = false),
        )
    }
}
