package com.dandi.nyummy.room.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * 보유 아이템. 같은 아이템을 여러 개 사면 한 개마다 따로 존재하며 좌표·장착 여부를 따로 가진다.
 *
 * [id]가 API의 ownedRoomItemId다. 상품은 [itemId]로 가리킨다.
 *
 * @property itemPurchaseLineId 구매한 구성품이면 그 id. 보상이면 null이다
 * @property firstEquippedAt 처음 장착한 시각. 환불 정책의 미착용 판단 기준이라 해제해도 지우지 않는다
 * @property revokedAt 받은 뒤 환불되어 회수한 시각. 사용자 삭제([deletedAt])와 달리 복구할 수 없다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "owned_room_items")
class OwnedRoomItem(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "item_id", nullable = false)
    val itemId: Long,

    @Column(name = "item_purchase_line_id")
    val itemPurchaseLineId: Long? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Column(name = "is_equipped", nullable = false)
    var isEquipped: Boolean = false

    @Column(name = "x", nullable = false)
    var x: Float = 0f

    @Column(name = "y", nullable = false)
    var y: Float = 0f

    @Column(name = "z_index", nullable = false)
    var zIndex: Int = 0

    @Column(name = "scale", nullable = false)
    var scale: Float = 1f

    @Column(name = "first_equipped_at")
    var firstEquippedAt: Instant? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    val isRevoked: Boolean
        get() = revokedAt != null

    /**
     * 장착하고 마이룸에서의 위치·크기를 바꾼다. 이미 장착한 아이템이면 위치·크기만 바뀐다.
     * 처음 장착하는 것이면 [now]를 처음 장착한 시각으로 남긴다.
     */
    fun equip(x: Float, y: Float, zIndex: Int, scale: Float, now: Instant) {
        this.isEquipped = true
        this.firstEquippedAt = this.firstEquippedAt ?: now
        this.x = x
        this.y = y
        this.zIndex = zIndex
        this.scale = scale
    }

    /**
     * 장착을 해제한다. 마지막 위치·크기는 그대로 남긴다.
     */
    fun unequip() {
        this.isEquipped = false
    }

    fun validateOwnership(requestUserId: Long) {
        if (userId != requestUserId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }
    }

    /**
     * 삭제 시각을 바꾼다. 시각을 넣으면 삭제, null을 넣으면 복구다.
     */
    fun updateDeletedAt(deletedAt: Instant?) {
        this.deletedAt = deletedAt
    }
}
