package com.dandi.nyummy.room.controller

import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.RoomErrorCode
import com.dandi.nyummy.language.CurrentLanguage
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.room.dto.OwnedRoomItemsResponse
import com.dandi.nyummy.room.dto.RoomResponse
import com.dandi.nyummy.room.dto.UpdateEquippedRoomItemsRequest
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.service.RoomService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Room", description = "마이룸 조회 · 보유 아이템 조회 · 장착 저장 · 보유 아이템 삭제 · 복구 API")
@RestController
@RequestMapping("/api/v1/rooms")
class RoomController(private val roomService: RoomService) {

    @Operation(
        summary = "마이룸 조회",
        description = "카테고리별 보유 아이템의 첫 묶음과 현재 장착한 아이템을 조회한다. " +
            "카테고리는 보유 아이템이 없어도 빈 목록으로 모두 내려가며, 순서는 고정이다. " +
            "각 카테고리의 보유 아이템은 장착한 아이템이 먼저 오고, 장착 여부가 같으면 최근 구매순이다. " +
            "카테고리별 다음 묶음은 보유 아이템 목록 조회 API에 해당 카테고리의 nextCursor를 cursor로 보내 조회한다. " +
            "equippedRoomItems는 방을 그리는 데 쓰며, 첫 묶음에 없는 아이템도 포함하고 zIndex 오름차순이다. " +
            "같은 아이템을 여러 개 샀으면 한 개씩 따로 내려가며, ownedRoomItemId는 구매한 아이템 한 개를 가리킨다. " +
            "이미지는 앱이 itemId로 https://cdn.nyummy.co.kr/items/{itemId}.png를 조립한다.",
    )
    @GetMapping
    fun getRoom(
        @CurrentUser user: AuthUser,
        @CurrentLanguage language: Language,
        @Parameter(description = "카테고리별로 처음 조회할 보유 아이템 개수 (1~50, 기본 10)")
        @RequestParam(defaultValue = "10")
        @Min(1)
        @Max(50)
        size: Int,
    ): RoomResponse = roomService.getRoom(user.userId, size, language)

    @Operation(
        summary = "보유 아이템 목록 조회",
        description = "한 카테고리의 보유 아이템을 조회한다. 무한 스크롤용 cursor 방식이다. " +
            "장착한 아이템이 먼저 오고, 장착 여부가 같으면 최근 구매순이다. " +
            "첫 요청은 cursor 없이 보내고, 다음 요청부터는 직전 응답의 nextCursor를 cursor로 보낸다. " +
            "hasNext가 false면 마지막 묶음이며 nextCursor는 null이다. " +
            "cursor가 없는 아이템이거나 다른 사용자의 것이면 400(api.common.invalidInputValue)이다. " +
            "스크롤 중 장착 상태가 바뀌면 묶음 사이에 아이템이 겹치거나 빠질 수 있으니, 꾸민 방을 저장한 뒤에는 첫 묶음부터 다시 조회한다. " +
            "이미지는 앱이 itemId로 https://cdn.nyummy.co.kr/items/{itemId}.png를 조립한다.",
    )
    @GetMapping("/owned-items")
    fun getOwnedRoomItems(
        @CurrentUser user: AuthUser,
        @Parameter(description = "아이템 카테고리") @RequestParam("category") category: RoomItemCategory,
        @Parameter(description = "직전 응답의 nextCursor. 첫 요청에서는 생략한다.") @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "한 번에 조회할 개수 (1~50, 기본 10)")
        @RequestParam(defaultValue = "10")
        @Min(1)
        @Max(50)
        size: Int,
    ): OwnedRoomItemsResponse = roomService.getOwnedRoomItems(user.userId, category, cursor, size)

    @Operation(
        summary = "꾸민 방 저장",
        description = "장착한 아이템 전체를 요청 목록으로 교체한다. " +
            "목록에 있는 보유 아이템은 장착하고 x · y · zIndex · scale을 갱신한다. " +
            "목록에 없는 보유 아이템은 장착을 해제하며, 마지막 좌표는 유지한다. " +
            "모두 해제하려면 빈 목록을 보낸다. x · y는 0 이상 100 이하이고, " +
            "scale은 원본 대비 배율로 0보다 커야 한다(1.0 = 원본). " +
            "같은 ownedRoomItemId를 두 번 보내면 400(api.room.duplicateOwnedRoomItem)이다. " +
            "없거나 삭제된 보유 아이템이 있으면 404, 다른 사용자의 보유 아이템만 섞여 있으면 403이며, 둘 다 있으면 404다. " +
            "거부되면 아무것도 바뀌지 않는다.",
    )
    @ApiResponse(responseCode = "204", description = "저장 성공")
    @ApiErrorCodes(
        room = [RoomErrorCode.DUPLICATE_OWNED_ROOM_ITEM, RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND],
        auth = [AuthErrorCode.FORBIDDEN],
    )
    @PutMapping("/equipped-items")
    fun updateEquippedRoomItems(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: UpdateEquippedRoomItemsRequest,
    ): ResponseEntity<Void> {
        roomService.updateEquippedRoomItems(user.userId, request)
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "보유 아이템 삭제",
        description = "보유 아이템 하나를 삭제한다. 장착 중인 아이템이면 장착을 해제하고 지우므로 마이룸에서도 빠진다. " +
            "실제로 지우지 않고 삭제 표시만 하므로 보유 아이템 복구 API로 되돌릴 수 있다. " +
            "이미 삭제한 보유 아이템에 다시 요청해도 성공한다.",
    )
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiErrorCodes(room = [RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND], auth = [AuthErrorCode.FORBIDDEN])
    @DeleteMapping("/owned-items/{ownedRoomItemId}")
    fun deleteOwnedRoomItem(
        @CurrentUser user: AuthUser,
        @Parameter(
            description = "삭제할 보유 아이템 ID (조회 응답의 ownedRoomItemId)",
        ) @PathVariable("ownedRoomItemId") ownedRoomItemId: Long,
    ): ResponseEntity<Void> {
        roomService.deleteOwnedRoomItem(user.userId, ownedRoomItemId)
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "보유 아이템 복구",
        description = "삭제한 보유 아이템을 되돌린다. 복구 기한은 없다. 삭제할 때 장착을 해제했으므로 장착하지 않은 채로 돌아오며, " +
            "마이룸에 다시 놓으려면 꾸민 방 저장 API로 저장한다. 삭제되지 않은 보유 아이템에 요청해도 그대로 두고 성공한다.",
    )
    @ApiResponse(responseCode = "204", description = "복구 성공")
    @ApiErrorCodes(room = [RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND], auth = [AuthErrorCode.FORBIDDEN])
    @PostMapping("/owned-items/{ownedRoomItemId}/restore")
    fun restoreOwnedRoomItem(
        @CurrentUser user: AuthUser,
        @Parameter(
            description = "복구할 보유 아이템 ID (삭제한 보유 아이템의 ownedRoomItemId)",
        ) @PathVariable("ownedRoomItemId") ownedRoomItemId: Long,
    ): ResponseEntity<Void> {
        roomService.restoreOwnedRoomItem(user.userId, ownedRoomItemId)
        return ResponseEntity.noContent().build()
    }
}
