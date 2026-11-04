package com.dandi.nyummy.room.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.RoomErrorCode
import com.dandi.nyummy.room.config.DefaultRoomItemProperties
import com.dandi.nyummy.room.config.RoomProperties
import com.dandi.nyummy.room.dto.EquippedRoomItemRequest
import com.dandi.nyummy.room.dto.EquippedRoomItemResult
import com.dandi.nyummy.room.dto.GrantedRoomItem
import com.dandi.nyummy.room.dto.OwnedRoomItemResult
import com.dandi.nyummy.room.dto.UpdateEquippedRoomItemsRequest
import com.dandi.nyummy.room.entity.OwnedRoomItem
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.repository.OwnedRoomItemRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.SliceImpl
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomServiceTest {

    private val userId = 1L
    private val now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val ownedRoomItemRepository = mockk<OwnedRoomItemRepository>()
    private val roomProperties = RoomProperties(
        defaultRoomItems = listOf(
            DefaultRoomItemProperties(itemId = 1L, x = 0f, y = 0f, zIndex = 0, scale = 1f),
            DefaultRoomItemProperties(itemId = 2L, x = 27f, y = 22f, zIndex = 2, scale = 0.8f),
        ),
    )

    private val roomService = RoomService(
        ownedRoomItemRepository = ownedRoomItemRepository,
        roomProperties = roomProperties,
        clock = clock,
    )

    private fun ownedRoomItem(id: Long, isEquipped: Boolean, ownerId: Long = userId) =
        OwnedRoomItem(userId = ownerId, itemId = 2L).apply {
            this.isEquipped = isEquipped
            // id는 DB가 채우는 val이라 테스트에서만 직접 넣는다.
            ReflectionTestUtils.setField(this, "id", id)
        }

    private fun ownedRoomItemResult(ownedRoomItemId: Long, isEquipped: Boolean = false) =
        OwnedRoomItemResult(ownedRoomItemId = ownedRoomItemId, name = "호박 쿠션", itemId = 2L, isEquipped = isEquipped)

    private fun givenOwnedRoomItems(vararg results: OwnedRoomItemResult, hasNext: Boolean = false) {
        every {
            ownedRoomItemRepository.findOwnedRoomItemsByUserIdBeforeCursor(any(), any(), any(), any(), any())
        } returns SliceImpl(results.toList(), PageRequest.of(0, 10), hasNext)
    }

    private fun equippedRoomItemRequest(ownedRoomItemId: Long, x: Float = 10f) =
        EquippedRoomItemRequest(ownedRoomItemId = ownedRoomItemId, x = x, y = 20f, zIndex = 3, scale = 0.5f)

    private fun givenOwnedRoomItemsToUpdate(vararg items: OwnedRoomItem) {
        every { ownedRoomItemRepository.findOwnedRoomItemsByUserIdAndDeletedAtIsNullAndRevokedAtIsNull(userId) } returns
            items.toList()
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    // createDefaultOwnedRoomItems

    @Test
    fun `설정한 기본 아이템을 장착한 채로 설정한 좌표에 지급한다`() {
        val savedItems = slot<List<OwnedRoomItem>>()
        every { ownedRoomItemRepository.saveAll(capture(savedItems)) } answers { firstArg() }

        roomService.createDefaultOwnedRoomItems(userId)

        val items = savedItems.captured
        assertEquals(listOf(1L, 2L), items.map { it.itemId })
        assertTrue(items.all { it.userId == userId && it.isEquipped })
        assertEquals(27f, items[1].x)
        assertEquals(22f, items[1].y)
        assertEquals(2, items[1].zIndex)
        assertEquals(0.8f, items[1].scale)
    }

    // createOwnedRoomItems

    @Test
    fun `받은 아이템 id마다 보유 아이템을 장착하지 않은 채로 하나씩 만든다`() {
        val savedItems = slot<List<OwnedRoomItem>>()
        every { ownedRoomItemRepository.saveAll(capture(savedItems)) } answers { firstArg() }

        roomService.createOwnedRoomItems(
            userId,
            listOf(GrantedRoomItem(2L), GrantedRoomItem(2L), GrantedRoomItem(3L, itemPurchaseLineId = 7L)),
        )

        val items = savedItems.captured
        assertEquals(listOf(2L, 2L, 3L), items.map { it.itemId })
        assertEquals(listOf(null, null, 7L), items.map { it.itemPurchaseLineId })
        assertTrue(items.all { it.userId == userId && !it.isEquipped })
    }

    // getOwnedRoomItems

    @Test
    fun `첫 요청은 장착한 아이템의 가장 큰 id를 기준으로 조회해 전부를 대상으로 한다`() {
        givenOwnedRoomItems()

        roomService.getOwnedRoomItems(userId, RoomItemCategory.BED, cursor = null, size = 10)

        verify {
            ownedRoomItemRepository.findOwnedRoomItemsByUserIdBeforeCursor(
                userId,
                RoomItemCategory.BED,
                true,
                Long.MAX_VALUE,
                PageRequest.of(0, 10),
            )
        }
    }

    @Test
    fun `커서가 있으면 커서 아이템의 장착 여부와 id를 기준으로 조회한다`() {
        every { ownedRoomItemRepository.findOwnedRoomItemByIdAndUserId(30L, userId) } returns
            ownedRoomItem(id = 30L, isEquipped = false)
        givenOwnedRoomItems()

        roomService.getOwnedRoomItems(userId, RoomItemCategory.BED, cursor = 30L, size = 10)

        verify {
            ownedRoomItemRepository.findOwnedRoomItemsByUserIdBeforeCursor(
                userId,
                RoomItemCategory.BED,
                false,
                30L,
                PageRequest.of(0, 10),
            )
        }
    }

    @Test
    fun `커서 아이템이 없거나 다른 사용자의 것이면 목록을 조회하지 않고 거부한다`() {
        every { ownedRoomItemRepository.findOwnedRoomItemByIdAndUserId(30L, userId) } returns null

        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            roomService.getOwnedRoomItems(userId, RoomItemCategory.BED, cursor = 30L, size = 10)
        }
        verify(exactly = 0) {
            ownedRoomItemRepository.findOwnedRoomItemsByUserIdBeforeCursor(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `다음 묶음이 있으면 마지막 보유 아이템 id를 nextCursor로 준다`() {
        givenOwnedRoomItems(ownedRoomItemResult(9, isEquipped = true), ownedRoomItemResult(7), hasNext = true)

        val result = roomService.getOwnedRoomItems(userId, RoomItemCategory.BED, cursor = null, size = 2)

        assertEquals(listOf(9L, 7L), result.ownedRoomItems.map { it.ownedRoomItemId })
        assertEquals(7L, result.nextCursor)
        assertTrue(result.hasNext)
    }

    @Test
    fun `마지막 묶음이면 nextCursor는 null이다`() {
        givenOwnedRoomItems(ownedRoomItemResult(3))

        val result = roomService.getOwnedRoomItems(userId, RoomItemCategory.BED, cursor = null, size = 2)

        assertNull(result.nextCursor)
        assertFalse(result.hasNext)
    }

    // getRoom

    @Test
    fun `보유 아이템이 없어도 모든 카테고리를 enum 순서대로 내려준다`() {
        givenOwnedRoomItems()
        every { ownedRoomItemRepository.findEquippedRoomItemsByUserId(userId) } returns emptyList()

        val room = roomService.getRoom(userId, size = 10)

        assertEquals(RoomItemCategory.entries, room.categories.map { it.category })
        assertEquals(RoomItemCategory.entries.map { it.description }, room.categories.map { it.categoryName })
        assertTrue(room.categories.all { it.ownedRoomItems.isEmpty() && !it.hasNext })
    }

    // getEquippedRoomItems

    @Test
    fun `장착한 아이템을 조회 순서 그대로 내려준다`() {
        every { ownedRoomItemRepository.findEquippedRoomItemsByUserId(userId) } returns listOf(
            EquippedRoomItemResult(5, RoomItemCategory.BACKGROUND, 1L, 0f, 0f, 0, 1f),
            EquippedRoomItemResult(6, RoomItemCategory.BED, 2L, 150f, 420f, 4, 1.2f),
        )

        val equippedRoomItems = roomService.getEquippedRoomItems(userId)

        assertEquals(listOf(5L, 6L), equippedRoomItems.map { it.ownedRoomItemId })
        assertEquals(2L, equippedRoomItems[1].itemId)
        assertEquals(1.2f, equippedRoomItems[1].scale)
    }

    // updateEquippedRoomItems

    @Test
    fun `장착하면 현재 시각을 처음 장착한 시각으로 남긴다`() {
        val item = ownedRoomItem(id = 5L, isEquipped = false)
        every { ownedRoomItemRepository.findOwnedRoomItemsByUserIdAndDeletedAtIsNullAndRevokedAtIsNull(userId) } returns
            listOf(item)

        roomService.updateEquippedRoomItems(userId, UpdateEquippedRoomItemsRequest(listOf(equippedRoomItemRequest(5L))))

        assertEquals(now, item.firstEquippedAt)
    }

    @Test
    fun `요청한 보유 아이템은 장착하고 위치와 크기를 바꾼다`() {
        val item = ownedRoomItem(id = 5L, isEquipped = false)
        givenOwnedRoomItemsToUpdate(item)

        roomService.updateEquippedRoomItems(
            userId,
            UpdateEquippedRoomItemsRequest(listOf(equippedRoomItemRequest(5L, x = 40f))),
        )

        assertTrue(item.isEquipped)
        assertEquals(40f, item.x)
        assertEquals(20f, item.y)
        assertEquals(3, item.zIndex)
        assertEquals(0.5f, item.scale)
    }

    @Test
    fun `요청에 없는 보유 아이템은 장착을 해제하고 마지막 위치는 남긴다`() {
        val kept = ownedRoomItem(id = 5L, isEquipped = true)
        val removed = ownedRoomItem(id = 6L, isEquipped = true).apply { x = 76f }
        givenOwnedRoomItemsToUpdate(kept, removed)

        roomService.updateEquippedRoomItems(userId, UpdateEquippedRoomItemsRequest(listOf(equippedRoomItemRequest(5L))))

        assertTrue(kept.isEquipped)
        assertFalse(removed.isEquipped)
        assertEquals(76f, removed.x)
    }

    @Test
    fun `빈 목록이면 모든 보유 아이템의 장착을 해제한다`() {
        val items = listOf(ownedRoomItem(id = 5L, isEquipped = true), ownedRoomItem(id = 6L, isEquipped = true))
        givenOwnedRoomItemsToUpdate(*items.toTypedArray())

        roomService.updateEquippedRoomItems(userId, UpdateEquippedRoomItemsRequest(emptyList()))

        assertTrue(items.none { it.isEquipped })
    }

    @Test
    fun `같은 보유 아이템이 두 번 있으면 보유 아이템을 조회하지 않고 거부한다`() {
        val request = UpdateEquippedRoomItemsRequest(listOf(equippedRoomItemRequest(5L), equippedRoomItemRequest(5L)))

        assertBusinessException(RoomErrorCode.DUPLICATE_OWNED_ROOM_ITEM) {
            roomService.updateEquippedRoomItems(userId, request)
        }
        verify(exactly = 0) {
            ownedRoomItemRepository.findOwnedRoomItemsByUserIdAndDeletedAtIsNullAndRevokedAtIsNull(any())
        }
    }

    @Test
    fun `없는 보유 아이템이 있으면 거부하고 장착 상태를 바꾸지 않는다`() {
        val item = ownedRoomItem(id = 5L, isEquipped = true)
        givenOwnedRoomItemsToUpdate(item)
        every { ownedRoomItemRepository.findAllById(setOf(99L)) } returns emptyList()
        val request = UpdateEquippedRoomItemsRequest(listOf(equippedRoomItemRequest(99L)))

        assertBusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND) {
            roomService.updateEquippedRoomItems(userId, request)
        }
        assertTrue(item.isEquipped)
    }

    @Test
    fun `삭제한 내 보유 아이템이 있으면 없는 아이템으로 보고 거부한다`() {
        givenOwnedRoomItemsToUpdate()
        every { ownedRoomItemRepository.findAllById(setOf(7L)) } returns
            listOf(ownedRoomItem(id = 7L, isEquipped = false))
        val request = UpdateEquippedRoomItemsRequest(listOf(equippedRoomItemRequest(7L)))

        assertBusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND) {
            roomService.updateEquippedRoomItems(userId, request)
        }
    }

    @Test
    fun `다른 사용자의 보유 아이템이 있으면 거부하고 장착 상태를 바꾸지 않는다`() {
        val item = ownedRoomItem(id = 5L, isEquipped = false)
        givenOwnedRoomItemsToUpdate(item)
        every { ownedRoomItemRepository.findAllById(setOf(8L)) } returns
            listOf(ownedRoomItem(id = 8L, isEquipped = false, ownerId = 2L))
        val request = UpdateEquippedRoomItemsRequest(listOf(equippedRoomItemRequest(5L), equippedRoomItemRequest(8L)))

        assertBusinessException(AuthErrorCode.FORBIDDEN) { roomService.updateEquippedRoomItems(userId, request) }
        assertFalse(item.isEquipped)
    }

    @Test
    fun `다른 사용자의 것과 없는 것이 함께 있으면 없는 아이템으로 거부한다`() {
        givenOwnedRoomItemsToUpdate()
        every { ownedRoomItemRepository.findAllById(setOf(8L, 99L)) } returns
            listOf(ownedRoomItem(id = 8L, isEquipped = false, ownerId = 2L))
        val request = UpdateEquippedRoomItemsRequest(listOf(equippedRoomItemRequest(8L), equippedRoomItemRequest(99L)))

        assertBusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND) {
            roomService.updateEquippedRoomItems(userId, request)
        }
    }

    // deleteOwnedRoomItem

    @Test
    fun `장착 중인 보유 아이템을 삭제하면 장착을 해제하고 삭제 시각을 기록한다`() {
        val item = ownedRoomItem(id = 5L, isEquipped = true).apply { x = 76f }
        every { ownedRoomItemRepository.findByIdOrNull(5L) } returns item

        roomService.deleteOwnedRoomItem(userId, 5L)

        assertFalse(item.isEquipped)
        assertEquals(now, item.deletedAt)
        assertEquals(76f, item.x)
    }

    @Test
    fun `이미 삭제한 보유 아이템을 다시 삭제해도 성공하고 삭제 시각이 바뀐다`() {
        val item = ownedRoomItem(id = 5L, isEquipped = false).apply { deletedAt = now.minusSeconds(600) }
        every { ownedRoomItemRepository.findByIdOrNull(5L) } returns item

        roomService.deleteOwnedRoomItem(userId, 5L)

        assertEquals(now, item.deletedAt)
    }

    @Test
    fun `회수된 보유 아이템은 없는 것으로 보고 삭제하지 않는다`() {
        val item = ownedRoomItem(id = 5L, isEquipped = false).apply { revokedAt = now.minusSeconds(60) }
        every { ownedRoomItemRepository.findByIdOrNull(5L) } returns item

        assertBusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND) {
            roomService.deleteOwnedRoomItem(userId, 5L)
        }
        assertNull(item.deletedAt)
    }

    @Test
    fun `없는 보유 아이템은 삭제할 수 없다`() {
        every { ownedRoomItemRepository.findByIdOrNull(99L) } returns null

        assertBusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND) {
            roomService.deleteOwnedRoomItem(userId, 99L)
        }
    }

    @Test
    fun `다른 사용자의 보유 아이템은 삭제할 수 없고 장착 상태도 그대로다`() {
        val item = ownedRoomItem(id = 8L, isEquipped = true, ownerId = 2L)
        every { ownedRoomItemRepository.findByIdOrNull(8L) } returns item

        assertBusinessException(AuthErrorCode.FORBIDDEN) { roomService.deleteOwnedRoomItem(userId, 8L) }
        assertTrue(item.isEquipped)
        assertNull(item.deletedAt)
    }

    // restoreOwnedRoomItem

    @Test
    fun `삭제한 보유 아이템을 복구하면 장착하지 않은 채로 돌아온다`() {
        val item = ownedRoomItem(id = 5L, isEquipped = false).apply { deletedAt = now.minusSeconds(600) }
        every { ownedRoomItemRepository.findByIdOrNull(5L) } returns item

        roomService.restoreOwnedRoomItem(userId, 5L)

        assertNull(item.deletedAt)
        assertFalse(item.isEquipped)
    }

    @Test
    fun `삭제되지 않은 보유 아이템을 복구해도 성공하고 장착 상태는 그대로다`() {
        val item = ownedRoomItem(id = 5L, isEquipped = true)
        every { ownedRoomItemRepository.findByIdOrNull(5L) } returns item

        roomService.restoreOwnedRoomItem(userId, 5L)

        assertNull(item.deletedAt)
        assertTrue(item.isEquipped)
    }

    @Test
    fun `회수된 보유 아이템은 없는 것으로 보고 복구하지 않는다`() {
        val deletedAt = now.minusSeconds(600)
        val item = ownedRoomItem(id = 5L, isEquipped = false).apply {
            this.deletedAt = deletedAt
            revokedAt = now.minusSeconds(60)
        }
        every { ownedRoomItemRepository.findByIdOrNull(5L) } returns item

        assertBusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND) {
            roomService.restoreOwnedRoomItem(userId, 5L)
        }
        assertEquals(deletedAt, item.deletedAt)
    }

    @Test
    fun `없는 보유 아이템은 복구할 수 없다`() {
        every { ownedRoomItemRepository.findByIdOrNull(99L) } returns null

        assertBusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND) {
            roomService.restoreOwnedRoomItem(userId, 99L)
        }
    }

    @Test
    fun `다른 사용자의 삭제한 보유 아이템은 복구할 수 없다`() {
        val deletedAt = now.minusSeconds(600)
        val item = ownedRoomItem(id = 8L, isEquipped = false, ownerId = 2L).apply { this.deletedAt = deletedAt }
        every { ownedRoomItemRepository.findByIdOrNull(8L) } returns item

        assertBusinessException(AuthErrorCode.FORBIDDEN) { roomService.restoreOwnedRoomItem(userId, 8L) }
        assertEquals(deletedAt, item.deletedAt)
    }
}
