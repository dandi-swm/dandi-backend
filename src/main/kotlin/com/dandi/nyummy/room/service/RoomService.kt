package com.dandi.nyummy.room.service

import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.RoomErrorCode
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.language.pickTranslation
import com.dandi.nyummy.room.config.RoomProperties
import com.dandi.nyummy.room.dto.EquippedRoomItemResponse
import com.dandi.nyummy.room.dto.GrantedRoomItem
import com.dandi.nyummy.room.dto.OwnedRoomItemsResponse
import com.dandi.nyummy.room.dto.RoomItemCategoryResponse
import com.dandi.nyummy.room.dto.RoomResponse
import com.dandi.nyummy.room.dto.UpdateEquippedRoomItemsRequest
import com.dandi.nyummy.room.entity.OwnedRoomItem
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.mapper.toEquippedRoomItemResponse
import com.dandi.nyummy.room.mapper.toOwnedRoomItem
import com.dandi.nyummy.room.mapper.toOwnedRoomItemResponse
import com.dandi.nyummy.room.repository.OwnedRoomItemRepository
import org.springframework.context.MessageSource
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class RoomService(
    private val coinService: CoinService,
    private val ownedRoomItemRepository: OwnedRoomItemRepository,
    private val roomProperties: RoomProperties,
    private val clock: Clock,
    private val messageSource: MessageSource,
) {

    /**
     * 가입한 사용자에게 기본 아이템을 장착한 채로 지급한다. 가입 트랜잭션 안에서 호출되어, 지급이 실패하면 가입도 실패한다.
     *
     * 아이템·좌표는 설정(app.room.default-room-items)에서 읽는다. 아이템 행(items·room_items)이 없는 DB에서는
     * 외래 키 제약에 걸려 가입이 실패하므로, 새 환경에는 기본 아이템 행을 먼저 넣는다.
     */
    @Transactional
    fun createDefaultOwnedRoomItems(userId: Long) {
        ownedRoomItemRepository.saveAll(roomProperties.defaultRoomItems.map { it.toOwnedRoomItem(userId) })
    }

    /**
     * [items]마다 보유 아이템을 하나씩 장착하지 않은 채로 만든다. 같은 아이템을 여러 개 주려면 그 수만큼 넣는다.
     * 우편 받기처럼 보상을 지급하는 쪽의 트랜잭션 안에서 호출한다.
     *
     * 마이룸 아이템(room_items)이 아닌 id가 있으면 외래 키 제약에 걸려 실패한다.
     */
    @Transactional
    fun createOwnedRoomItems(userId: Long, items: List<GrantedRoomItem>) {
        ownedRoomItemRepository.saveAll(
            items.map {
                OwnedRoomItem(userId = userId, itemId = it.itemId, itemPurchaseLineId = it.itemPurchaseLineId)
            },
        )
    }

    /**
     * 카테고리별 보유 아이템의 첫 묶음과 장착한 아이템을 조회한다.
     *
     * 카테고리는 보유 아이템이 없어도 enum 순서대로 모두 내려간다. 카테고리마다 [getOwnedRoomItems]와 같은 쿼리를
     * 따로 날린다. 앱이 카테고리를 오가며 다음 묶음을 받을 때도 같은 쿼리를 쓰므로, 전체를 한 번에 읽어 나누지 않는다.
     *
     * @param language 요청의 `X-Language`로 정한 앱 언어. 카테고리 이름과 아이템 이름을 이 언어로 꺼낸다
     */
    @Transactional(readOnly = true)
    fun getRoom(userId: Long, size: Int, language: Language): RoomResponse = RoomResponse(
        categories = RoomItemCategory.entries.map { category ->
            val ownedRoomItems = getOwnedRoomItems(userId, category, null, size, language)
            RoomItemCategoryResponse(
                category = category,
                categoryName = messageSource.getMessage("roomItemCategory.${category.name}", language),
                ownedRoomItems = ownedRoomItems.ownedRoomItems,
                nextCursor = ownedRoomItems.nextCursor,
                hasNext = ownedRoomItems.hasNext,
            )
        },
        equippedRoomItems = getEquippedRoomItems(userId),
    )

    /**
     * 한 카테고리의 보유 아이템을 장착한 것 먼저, 같은 장착 여부 안에서는 최신순으로 [size]개씩 조회한다.
     *
     * 정렬이 장착 여부와 id 두 값이라 [cursor]만으로는 위치를 알 수 없어, 커서 아이템을 먼저 읽어 장착 여부를 얻는다.
     * 스크롤 중에 지운 아이템도 정렬 위치는 남아 있으므로 삭제된 커서 아이템도 그대로 쓴다.
     * 커서 아이템이 없거나 다른 사용자의 것이면 처음부터 조회하지 않고 거부한다. 처음부터 주면 앱이 첫 묶음을
     * 뒤에 이어 붙여 중복이 생기고, 다른 사용자의 id가 있는지도 드러나지 않게 같은 응답으로 묶는다.
     *
     * @param cursor 직전 묶음의 마지막 ownedRoomItemId. null이면 처음부터 조회한다.
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 커서 아이템이 없거나 다른 사용자의 것인 경우
     */
    @Transactional(readOnly = true)
    fun getOwnedRoomItems(
        userId: Long,
        category: RoomItemCategory,
        cursor: Long?,
        size: Int,
        language: Language,
    ): OwnedRoomItemsResponse {
        val cursorItem = cursor?.let {
            ownedRoomItemRepository.findOwnedRoomItemByIdAndUserId(it, userId)
                ?: throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE)
        }

        val ownedRoomItems = ownedRoomItemRepository.findOwnedRoomItemsByUserIdBeforeCursor(
            userId = userId,
            category = category,
            cursorIsEquipped = cursorItem?.isEquipped ?: true,
            cursorId = cursorItem?.id ?: Long.MAX_VALUE,
            pageable = PageRequest.of(0, size),
        )

        return OwnedRoomItemsResponse(
            ownedRoomItems = ownedRoomItems.content.map {
                it.toOwnedRoomItemResponse(name = it.nameTranslations.pickTranslation(language, it.name))
            },
            nextCursor = if (ownedRoomItems.hasNext()) ownedRoomItems.content.last().ownedRoomItemId else null,
            hasNext = ownedRoomItems.hasNext(),
        )
    }

    /**
     * 장착한 아이템을 그리는 순서(zIndex 오름차순)로 조회한다. 마이룸 조회와 홈이 함께 쓴다.
     */
    @Transactional(readOnly = true)
    fun getEquippedRoomItems(userId: Long): List<EquippedRoomItemResponse> =
        ownedRoomItemRepository.findEquippedRoomItemsByUserId(userId).map { it.toEquippedRoomItemResponse() }

    /**
     * 장착한 아이템 전체를 요청 목록으로 교체한다. 목록에 있는 보유 아이템은 장착하고 위치·크기를 바꾸고,
     * 목록에 없는 보유 아이템은 장착을 해제한다. 해제한 아이템의 마지막 위치·크기는 남긴다.
     *
     * 사용자의 삭제되지 않은 보유 아이템을 모두 읽고 바뀐 행만 변경 감지로 UPDATE한다.
     * 이미 해제된 아이템에 [OwnedRoomItem.unequip]을 불러도 값이 같아 UPDATE가 나가지 않으므로 따로 분기하지 않는다.
     *
     * 보유 아이템 UPDATE는 모든 컬럼을 쓰므로, 읽은 뒤 다른 트랜잭션이 커밋한 값을 되돌릴 수 있다. 그래서 읽기 전에
     * 코인 행을 사용자 단위 자물쇠로 잠근다([CoinService.lockCoin]). 잠그지 않으면 지운 아이템이 살아나거나, 환불로
     * 회수한 아이템의 revoked_at이 지워져 코인과 아이템을 둘 다 갖게 된다. 같은 사용자의 삭제·복구도 같은 자물쇠를 쓴다.
     *
     * @throws BusinessException [RoomErrorCode.DUPLICATE_OWNED_ROOM_ITEM] 같은 ownedRoomItemId가 두 번 이상 있는 경우
     * @throws BusinessException [RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND] 없거나 삭제된 보유 아이템이 있는 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 내 보유 아이템이 아닌 것이 모두 다른 사용자의 것인 경우
     */
    @Transactional
    fun updateEquippedRoomItems(userId: Long, request: UpdateEquippedRoomItemsRequest) {
        val requestItems = request.equippedRoomItems.associateBy { it.ownedRoomItemId }
        if (requestItems.size != request.equippedRoomItems.size) {
            throw BusinessException(RoomErrorCode.DUPLICATE_OWNED_ROOM_ITEM)
        }

        coinService.lockCoin(userId)
        val ownedRoomItems = ownedRoomItemRepository.findOwnedRoomItemsByUserIdAndDeletedAtIsNullAndRevokedAtIsNull(
            userId,
        )
        validateMissingOwnedRoomItems(userId, requestItems.keys - ownedRoomItems.map { it.id }.toSet())

        val now = Instant.now(clock)
        ownedRoomItems.forEach { ownedRoomItem ->
            val requestItem = requestItems[ownedRoomItem.id]
            if (requestItem != null) {
                ownedRoomItem.equip(requestItem.x, requestItem.y, requestItem.zIndex, requestItem.scale, now)
            } else {
                ownedRoomItem.unequip()
            }
        }
    }

    // 요청 id 중 내 삭제되지 않은 보유 아이템에 없는 것을 거부한다. 없는 것이나 삭제한 내 것이 하나라도 있으면 404,
    // 모두 다른 사용자의 것일 때만 403이다. 다른 사용자의 것인지 가리려고 id로 한 번 더 조회한다.
    private fun validateMissingOwnedRoomItems(userId: Long, missingIds: Set<Long>) {
        if (missingIds.isEmpty()) {
            return
        }

        val missingItems = ownedRoomItemRepository.findAllById(missingIds)
        if (missingItems.size < missingIds.size || missingItems.any { it.userId == userId }) {
            throw BusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND)
        }
        throw BusinessException(AuthErrorCode.FORBIDDEN)
    }

    /**
     * 보유 아이템을 소프트 삭제한다. 삭제 직후 앱의 되돌리기로 복구할 수 있도록 행은 남긴다.
     *
     * 장착 중이면 장착을 해제한 뒤 지운다. 그래서 마이룸에서도 빠지고, 복구하면 장착하지 않은 채로 돌아온다.
     * 마지막 위치·크기는 남긴다.
     *
     * 이미 삭제된 보유 아이템도 그대로 성공한다(멱등). 삭제를 두 번 눌러도 결과가 같게 하기 위함이다.
     * 이때 삭제 시각은 다시 삭제한 시각으로 바뀐다.
     *
     * @throws BusinessException [RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND] 보유 아이템이 없거나 회수된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 보유 아이템인 경우
     */
    @Transactional
    fun deleteOwnedRoomItem(userId: Long, ownedRoomItemId: Long) {
        coinService.lockCoin(userId)
        val ownedRoomItem = ownedRoomItemRepository.findByIdOrNull(ownedRoomItemId)?.takeUnless { it.isRevoked }
            ?: throw BusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND)

        ownedRoomItem.validateOwnership(userId)
        ownedRoomItem.unequip()
        ownedRoomItem.updateDeletedAt(Instant.now(clock))
    }

    /**
     * 소프트 삭제한 보유 아이템을 되돌린다. 삭제 직후 앱의 되돌리기에서 호출하며, 복구 기한은 두지 않는다.
     *
     * 삭제할 때 장착을 해제했으므로 장착하지 않은 채로 보유 목록에 돌아온다.
     *
     * 삭제되지 않은 보유 아이템도 그대로 성공한다(멱등). 되돌리기를 여러 번 눌러도 결과가 같게 하기 위함이다.
     * deletedAt이 이미 null이면 같은 값을 넣어도 변경 감지에 걸리지 않아 UPDATE가 나가지 않으므로 따로 분기하지 않는다.
     *
     * @throws BusinessException [RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND] 보유 아이템이 없거나 회수된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 보유 아이템인 경우
     */
    @Transactional
    fun restoreOwnedRoomItem(userId: Long, ownedRoomItemId: Long) {
        coinService.lockCoin(userId)
        val ownedRoomItem = ownedRoomItemRepository.findByIdOrNull(ownedRoomItemId)?.takeUnless { it.isRevoked }
            ?: throw BusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND)

        ownedRoomItem.validateOwnership(userId)
        ownedRoomItem.updateDeletedAt(null)
    }
}
