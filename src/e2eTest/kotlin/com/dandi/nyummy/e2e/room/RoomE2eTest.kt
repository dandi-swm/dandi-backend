package com.dandi.nyummy.e2e.room

import com.dandi.nyummy.e2e.support.AuthFixture
import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import io.restassured.specification.RequestSpecification
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RoomE2eTest {
    // 조회만 하므로 prod에서도 돌린다. 모든 사용자는 가입할 때(기존 사용자는 채우기 SQL로) 기본 아이템을
    // 장착한 채로 받으므로 고정 계정에도 배경과 장착 아이템이 있다.
    @Smoke
    @Test
    fun `마이룸을 조회하면 모든 카테고리가 고정 순서로 오고 기본 아이템이 장착돼 있다`() {
        val zIndexes: List<Int> = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/rooms")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("categories", "equippedRoomItems"))
            // 앱은 이 순서대로 탭을 그린다. 카테고리가 바뀌거나 순서가 달라지면 화면이 어긋난다.
            body("categories.category", equalTo(ROOM_ITEM_CATEGORIES))
            body(
                "categories[0].keySet()",
                hasItems("category", "categoryName", "ownedRoomItems", "nextCursor", "hasNext"),
            )
            body(
                "categories.find { it.category == 'BACKGROUND' }.ownedRoomItems[0].keySet()",
                hasItems("ownedRoomItemId", "name", "itemId", "isEquipped"),
            )
            body("equippedRoomItems", not(empty<Any>()))
            body(
                "equippedRoomItems[0].keySet()",
                hasItems("ownedRoomItemId", "category", "itemId", "x", "y", "zIndex", "scale"),
            )
        } Extract {
            path("equippedRoomItems.zIndex")
        }

        // 앱은 받은 순서대로 겹쳐 그리므로 zIndex 오름차순이어야 배경이 맨 뒤에 깔린다.
        assertEquals(zIndexes.sorted(), zIndexes, "장착 아이템이 zIndex 오름차순이 아닙니다: $zIndexes")
    }

    @Smoke
    @Test
    fun `한 카테고리의 보유 아이템 목록을 조회한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParam("category", "BED")
        } When {
            get("/api/v1/rooms/owned-items")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("ownedRoomItems", "nextCursor", "hasNext"))
            body("ownedRoomItems[0].keySet()", hasItems("ownedRoomItemId", "name", "itemId", "isEquipped"))
        }
    }

    // 처음부터 다시 주면 앱이 첫 묶음을 뒤에 이어 붙여 중복이 생기므로 거부해야 한다.
    @Test
    fun `내 것이 아닌 커서로 보유 아이템 목록을 조회하면 거부한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("category" to "BED", "cursor" to Long.MAX_VALUE))
        } When {
            get("/api/v1/rooms/owned-items")
        } Then {
            statusCode(400)
            body("code", equalTo("api.common.invalidInputValue"))
        }
    }

    // 아래 세 테스트는 고정 계정의 장착 상태를 바꾸므로 prod smoke에서는 돌리지 않는다. 시작할 때 장착 목록을 읽어 두고
    // finally에서 그 목록으로 다시 저장해 되돌린다. 고정 계정은 기본 아이템을 카테고리마다 하나씩 장착하고 있다.
    @Test
    fun `꾸민 방을 저장하면 장착 아이템이 요청 목록으로 바뀐다`() {
        val before = getEquippedRoomItems()
        val etcItem = findEquippedRoomItem(before, "ETC")
        val bedItem = findEquippedRoomItem(before, "BED")

        try {
            // ETC 아이템은 빼고 BED 아이템은 옮긴다
            val request = before.filter { it != etcItem }.map { item ->
                val requestItem = item.toRequestItem()
                if (item == bedItem) requestItem + mapOf("x" to 50.5f, "y" to 60.5f, "zIndex" to 9) else requestItem
            }
            updateEquippedRoomItems(request).statusCode(204)

            val after = getEquippedRoomItems()
            assertEquals(request.map { it.ownedRoomItemId }.toSet(), after.map { it.ownedRoomItemId }.toSet())
            val movedBedItem = after.first { it.ownedRoomItemId == bedItem.ownedRoomItemId }
            assertEquals(50.5f, (movedBedItem.getValue("x") as Number).toFloat())
            assertEquals(60.5f, (movedBedItem.getValue("y") as Number).toFloat())
            assertEquals(9, movedBedItem["zIndex"])

            // 목록에서 뺀 아이템은 지우지 않고 장착만 해제한다
            val etcOwnedItem = findOwnedRoomItem("ETC", etcItem.ownedRoomItemId)
            assertEquals(false, etcOwnedItem?.get("isEquipped"), "목록에서 뺀 아이템이 장착 해제된 채로 보유 목록에 없습니다")
        } finally {
            updateEquippedRoomItems(before.map { it.toRequestItem() })
        }
    }

    // 거부(4xx)여도 일부가 먼저 저장됐을 수 있으므로, 정상 아이템은 옮긴 좌표로 함께 보내 부분 저장이 드러나게 한다.
    @Test
    fun `잘못된 목록으로 꾸민 방을 저장하면 거부하고 장착 상태를 바꾸지 않는다`() {
        val otherSpec = RequestSpecs.createAuthorizedSpec(AuthFixture.otherAccessToken)
        val before = getEquippedRoomItems()
        val otherBefore = getEquippedRoomItems(otherSpec)
        val otherItemId = assertNotNull(otherBefore.firstOrNull(), "Other 계정에 장착한 아이템이 없습니다").ownedRoomItemId
        val moved = before.map { it.toRequestItem() + mapOf("x" to 50.5f, "y" to 60.5f) }
        val first = moved.first()

        try {
            updateEquippedRoomItems(moved + first).validateError(400, "api.room.duplicateOwnedRoomItem")
            updateEquippedRoomItems(moved + createRequestItem(otherItemId)).validateError(403, "api.auth.forbidden")
            updateEquippedRoomItems(moved + createRequestItem(Long.MAX_VALUE))
                .validateError(404, "api.room.ownedRoomItemNotFound")
            // 남의 것과 없는 것이 섞이면 없는 아이템으로 거부한다
            updateEquippedRoomItems(moved + createRequestItem(otherItemId) + createRequestItem(Long.MAX_VALUE))
                .validateError(404, "api.room.ownedRoomItemNotFound")
            updateEquippedRoomItems(listOf(first + mapOf("x" to 101f)))
                .validateError(400, "api.common.invalidInputValue")
            updateEquippedRoomItems(listOf(first + mapOf("scale" to 0f)))
                .validateError(400, "api.common.invalidInputValue")

            assertEquals(before, getEquippedRoomItems(), "거부된 저장 뒤 장착 상태가 바뀌었습니다")
            assertEquals(otherBefore, getEquippedRoomItems(otherSpec), "거부된 저장 뒤 다른 사용자의 장착 상태가 바뀌었습니다")
        } finally {
            updateEquippedRoomItems(before.map { it.toRequestItem() })
        }
    }

    @Test
    fun `보유 아이템을 삭제·복구한다`() {
        val ownerSpec = RequestSpecs.createAuthorizedSpec()
        val otherSpec = RequestSpecs.createAuthorizedSpec(AuthFixture.otherAccessToken)
        val before = getEquippedRoomItems()
        val etcItemId = findEquippedRoomItem(before, "ETC").ownedRoomItemId

        try {
            // 1. 남의 보유 아이템은 삭제·복구할 수 없다. 내 아이템은 장착된 채로 남는다.
            Given {
                spec(otherSpec)
            } When {
                delete("/api/v1/rooms/owned-items/$etcItemId")
            } Then {
                validateError(403, "api.auth.forbidden")
            }
            Given {
                spec(otherSpec)
            } When {
                post("/api/v1/rooms/owned-items/$etcItemId/restore")
            } Then {
                validateError(403, "api.auth.forbidden")
            }
            assertEquals(before, getEquippedRoomItems(), "거부된 요청 뒤 장착 상태가 바뀌었습니다")

            // 2. 삭제: 마이룸과 보유 목록에서 빠진다. 지운 아이템으로 저장하면 없는 아이템으로 거부한다.
            Given { spec(ownerSpec) } When { delete("/api/v1/rooms/owned-items/$etcItemId") } Then { statusCode(204) }
            val afterDelete = getEquippedRoomItems()
            assertNull(afterDelete.find { it.ownedRoomItemId == etcItemId }, "삭제한 아이템이 마이룸에 남아 있습니다")
            assertNull(findOwnedRoomItem("ETC", etcItemId), "삭제한 아이템이 보유 목록에 남아 있습니다")
            updateEquippedRoomItems(before.map { it.toRequestItem() })
                .validateError(404, "api.room.ownedRoomItemNotFound")
            assertEquals(afterDelete, getEquippedRoomItems(), "거부된 저장 뒤 장착 상태가 바뀌었습니다")

            // 다시 삭제해도 성공한다
            Given { spec(ownerSpec) } When { delete("/api/v1/rooms/owned-items/$etcItemId") } Then { statusCode(204) }

            // 3. 복구: 장착하지 않은 채로 보유 목록에 돌아온다. 다시 복구해도 성공한다.
            Given {
                spec(ownerSpec)
            } When {
                post("/api/v1/rooms/owned-items/$etcItemId/restore")
            } Then {
                statusCode(204)
            }
            assertEquals(
                false,
                findOwnedRoomItem("ETC", etcItemId)?.get("isEquipped"),
                "복구한 아이템이 장착 해제된 채로 보유 목록에 없습니다",
            )
            assertNull(getEquippedRoomItems().find { it.ownedRoomItemId == etcItemId }, "복구한 아이템이 장착돼 있습니다")
            Given {
                spec(ownerSpec)
            } When {
                post("/api/v1/rooms/owned-items/$etcItemId/restore")
            } Then {
                statusCode(204)
            }
        } finally {
            // 중간에 실패했을 수 있어 복구한 뒤 처음 장착 목록으로 다시 저장한다. 결과는 검증하지 않는다.
            Given { spec(ownerSpec) } When { post("/api/v1/rooms/owned-items/$etcItemId/restore") }
            updateEquippedRoomItems(before.map { it.toRequestItem() })
        }
    }

    private fun getEquippedRoomItems(spec: RequestSpecification = RequestSpecs.createAuthorizedSpec()) = Given {
        spec(spec)
    } When {
        get("/api/v1/rooms")
    } Then {
        statusCode(200)
    } Extract {
        path<List<Map<String, Any?>>>("equippedRoomItems")
    }

    private fun findEquippedRoomItem(equippedRoomItems: List<Map<String, Any?>>, category: String) = assertNotNull(
        equippedRoomItems.find { it["category"] == category },
        "고정 계정에 장착한 $category 아이템이 없습니다",
    )

    // 고정 계정은 카테고리마다 아이템이 몇 개뿐이라 한 묶음(최대 50개)에서 찾는다.
    private fun findOwnedRoomItem(category: String, ownedRoomItemId: Long): Map<String, Any?>? = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        queryParams(mapOf("category" to category, "size" to 50))
    } When {
        get("/api/v1/rooms/owned-items")
    } Then {
        statusCode(200)
    } Extract {
        path("ownedRoomItems.find { it.ownedRoomItemId == $ownedRoomItemId }")
    }

    private fun updateEquippedRoomItems(equippedRoomItems: List<Map<String, Any?>>): ValidatableResponse = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        body(mapOf("equippedRoomItems" to equippedRoomItems))
    } When {
        put("/api/v1/rooms/equipped-items")
    } Then {
        // 응답 검증은 호출하는 쪽에서 한다. finally의 되돌리기는 결과를 보지 않는다.
    }

    private fun createRequestItem(ownedRoomItemId: Long): Map<String, Any?> =
        mapOf("ownedRoomItemId" to ownedRoomItemId, "x" to 10f, "y" to 10f, "zIndex" to 0, "scale" to 1f)

    // 조회 응답의 장착 아이템 한 건을 저장 요청 한 건으로 바꾼다
    private fun Map<String, Any?>.toRequestItem(): Map<String, Any?> = mapOf(
        "ownedRoomItemId" to this["ownedRoomItemId"],
        "x" to this["x"],
        "y" to this["y"],
        "zIndex" to this["zIndex"],
        "scale" to this["scale"],
    )

    // JSON 숫자는 크기에 따라 Integer나 Long으로 읽히므로 Long으로 맞춘다
    private val Map<String, Any?>.ownedRoomItemId: Long
        get() = (getValue("ownedRoomItemId") as Number).toLong()

    private fun ValidatableResponse.validateError(status: Int, code: String) {
        statusCode(status)
        body("code", equalTo(code))
    }

    companion object {
        private val ROOM_ITEM_CATEGORIES = listOf("BACKGROUND", "BED", "CAT_WHEEL", "RUG", "ETC")
    }
}
