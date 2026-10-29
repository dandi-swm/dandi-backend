package com.dandi.nyummy.shop.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
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
 * 추천 탭 배너. 프로모션 하나를 알리고, 누르면 그 프로모션 화면을 연다. [id]가 앱의 bannerId다.
 *
 * 2주~한 달마다 바꾸므로 배포 없이 어드민에서 바꾼다. 내릴 때는 지우지 않고 [endsAt]에 지금 시각을 넣는다.
 * 모서리(놓인 자리가 정함)와 가격표(프로모션 상품의 정가 합·결제가 합)는 계산하는 값이라 저장하지 않는다.
 *
 * @property promotionId 알리는 프로모션. 다른 프로모션을 알리려면 새 배너를 만든다
 * @property title 평문. 서버가 RichText로 감싼다
 * @property titleColor SDUI 색 토큰(contentPrimary 등). 어두운 그림 위에서는 contentOnInverse
 * @property displayOrder 추천 탭의 노출 순서. 작은 값이 앞이다
 * @property endsAt 내리는 시각. null이면 내릴 때까지 노출한다
 * @property adminId 마지막으로 바꾼 운영자
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "shop_banners")
class ShopBanner(
    @Column(name = "promotion_id", nullable = false)
    val promotionId: Long,

    @Column(name = "image_key", nullable = false, length = 512)
    var imageKey: String,

    @Column(name = "title", nullable = false, length = 100)
    var title: String,

    @Column(name = "description")
    var description: String? = null,

    @Column(name = "title_color", nullable = false, length = 30)
    var titleColor: String,

    @Column(name = "description_color", length = 30)
    var descriptionColor: String? = null,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int,

    @Column(name = "starts_at", nullable = false)
    var startsAt: Instant,

    @Column(name = "ends_at")
    var endsAt: Instant? = null,

    @Column(name = "admin_id", nullable = false)
    var adminId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    companion object {
        /**
         * 노출 기간 [startsAt, endsAt)이 비어 있지 않은지 확인한다. [endsAt]이 null이면 내릴 때까지 노출한다.
         *
         * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 종료 시각이 시작 시각과 같거나 앞인 경우
         */
        fun validatePeriod(startsAt: Instant, endsAt: Instant?) {
            if (endsAt != null && !endsAt.isAfter(startsAt)) {
                throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE, "배너 종료 시각은 시작 시각보다 뒤여야 합니다.")
            }
        }
    }

    fun updateImageKey(imageKey: String) {
        this.imageKey = imageKey
    }

    fun updateTitle(title: String) {
        this.title = title
    }

    fun updateDescription(description: String?) {
        this.description = description
    }

    fun updateTitleColor(titleColor: String) {
        this.titleColor = titleColor
    }

    fun updateDescriptionColor(descriptionColor: String?) {
        this.descriptionColor = descriptionColor
    }

    fun updateDisplayOrder(displayOrder: Int) {
        this.displayOrder = displayOrder
    }

    /**
     * 노출 기간을 바꾼다. 배너를 내릴 때는 [endsAt]에 지금 시각을 넣는다.
     *
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 종료 시각이 시작 시각과 같거나 앞인 경우
     */
    fun updatePeriod(startsAt: Instant, endsAt: Instant?) {
        validatePeriod(startsAt, endsAt)
        this.startsAt = startsAt
        this.endsAt = endsAt
    }

    fun updateAdminId(adminId: Long) {
        this.adminId = adminId
    }
}
