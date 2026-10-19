package com.dandi.nyummy.mail.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.MailErrorCode
import com.dandi.nyummy.mail.enum.MailCategory
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * 우편. 보상 내용은 [category]에 따라 다르다.
 * COIN은 [coinQuantity]만, ITEM은 [MailItem]만, REWARD_BOX는 둘 다 가질 수 있다.
 *
 * ITEM 우편의 목록 이미지는 우편을 만들 때 정한 [representativeItemId]로 만든다.
 * REWARD_BOX 우편은 지급할 때 뽑은 [rewardBoxCategory]를 가진다. 다른 종류는 null이다.
 * [expiresAt]이 null이면 만료되지 않는 우편이다.
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "mails")
class Mail(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    val category: MailCategory,

    @Column(name = "title", nullable = false, length = 100)
    val title: String,

    @Column(name = "content", nullable = false, length = 255)
    val content: String,

    @Column(name = "coin_quantity")
    val coinQuantity: Int? = null,

    @Column(name = "representative_item_id")
    val representativeItemId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_box_category", length = 20)
    val rewardBoxCategory: RewardBoxCategory? = null,

    @Column(name = "expires_at")
    val expiresAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Column(name = "claimed_at")
    var claimedAt: Instant? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    val isClaimed: Boolean
        get() = claimedAt != null

    /**
     * [now] 기준으로 만료됐는지 확인한다. 목록 조회와 같은 기준이다(expiresAt이 now보다 뒤여야 유효).
     */
    fun isExpired(now: Instant): Boolean = expiresAt?.let { !it.isAfter(now) } ?: false

    fun validateOwnership(requestUserId: Long) {
        if (userId != requestUserId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }
    }

    /**
     * 우편을 받은 상태로 바꾼다. 이미 받았거나 [claimedAt] 기준으로 만료된 우편은 받을 수 없다.
     *
     * @throws BusinessException [MailErrorCode.ALREADY_CLAIMED] 이미 받은 우편인 경우
     * @throws BusinessException [MailErrorCode.MAIL_EXPIRED] 만료된 우편인 경우
     */
    fun claim(claimedAt: Instant) {
        if (isClaimed) {
            throw BusinessException(MailErrorCode.ALREADY_CLAIMED)
        }
        if (isExpired(claimedAt)) {
            throw BusinessException(MailErrorCode.MAIL_EXPIRED)
        }

        this.claimedAt = claimedAt
    }
}
