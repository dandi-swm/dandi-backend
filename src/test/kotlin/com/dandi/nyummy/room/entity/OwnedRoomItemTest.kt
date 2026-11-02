package com.dandi.nyummy.room.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnedRoomItemTest {

    private val now = Instant.parse("2026-10-19T03:00:00Z")

    private fun ownedRoomItem(isEquipped: Boolean = false) = OwnedRoomItem(userId = 1L, itemId = 2L).apply {
        this.isEquipped = isEquipped
        x = 27f
        y = 22f
        zIndex = 2
        scale = 0.8f
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    // equip

    @Test
    fun `장착하면 위치와 크기를 요청한 값으로 바꾼다`() {
        val item = ownedRoomItem()

        item.equip(x = 40f, y = 60f, zIndex = 5, scale = 1.2f, now = now)

        assertTrue(item.isEquipped)
        assertEquals(40f, item.x)
        assertEquals(60f, item.y)
        assertEquals(5, item.zIndex)
        assertEquals(1.2f, item.scale)
    }

    @Test
    fun `이미 장착한 아이템을 다시 장착하면 장착한 채로 위치만 바뀐다`() {
        val item = ownedRoomItem(isEquipped = true)

        item.equip(x = 0f, y = 100f, zIndex = 0, scale = 1f, now = now)

        assertTrue(item.isEquipped)
        assertEquals(0f, item.x)
        assertEquals(100f, item.y)
    }

    @Test
    fun `처음 장착한 시각은 처음 장착할 때만 남기고 해제해도 지우지 않는다`() {
        val item = ownedRoomItem()

        item.equip(x = 40f, y = 60f, zIndex = 5, scale = 1.2f, now = now)
        item.unequip()
        item.equip(x = 40f, y = 60f, zIndex = 5, scale = 1.2f, now = now.plusSeconds(60))

        assertEquals(now, item.firstEquippedAt)
    }

    // unequip

    @Test
    fun `장착을 해제해도 마지막 위치와 크기는 남는다`() {
        val item = ownedRoomItem(isEquipped = true)

        item.unequip()

        assertFalse(item.isEquipped)
        assertEquals(27f, item.x)
        assertEquals(22f, item.y)
        assertEquals(2, item.zIndex)
        assertEquals(0.8f, item.scale)
    }

    // validateOwnership

    @Test
    fun `다른 사용자의 보유 아이템이면 거부한다`() {
        assertBusinessException(AuthErrorCode.FORBIDDEN) { ownedRoomItem().validateOwnership(requestUserId = 2L) }
    }

    @Test
    fun `내 보유 아이템이면 통과한다`() {
        ownedRoomItem().validateOwnership(requestUserId = 1L)
    }

    // updateDeletedAt

    @Test
    fun `삭제 시각을 넣으면 삭제되고 null을 넣으면 복구된다`() {
        val item = ownedRoomItem()
        val deletedAt = Instant.parse("2026-10-19T03:00:00Z")

        item.updateDeletedAt(deletedAt)
        assertEquals(deletedAt, item.deletedAt)

        item.updateDeletedAt(null)
        assertNull(item.deletedAt)
    }
}
