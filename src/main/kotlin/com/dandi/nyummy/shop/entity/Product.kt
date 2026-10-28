package com.dandi.nyummy.shop.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.room.enum.RoomItemCategory
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
 * 상품(판매 단위). 단품은 구성품([ProductItem]) 하나, 세트는 여러 개다. [id]가 앱의 productId다.
 *
 * 게임 아이템([com.dandi.nyummy.item.entity.Item])은 물건이고, 상품은 그 물건을 파는 단위다.
 * 구매·환불 기록이 상품을 가리키므로 지우지 않고, 판매를 멈출 때는 [saleEndsAt]에 지금 시각을 넣는다.
 *
 * @property category 탭과 분류 칩을 거르는 기준. 단품은 구성품의 분류, 세트는 운영자가 정한다
 * @property representativeItemId 카드에 그릴 그림. 단품은 그 아이템, 세트는 대표 구성품이다
 * @property price 1개 정가. 할인가는 저장하지 않고 진행 중인 프로모션의 할인율로 계산한다.
 *   구성품 정가와 어긋나지 않게 바꾸지 않는다(정가를 바꾸려면 판매를 멈추고 새 상품을 만든다)
 * @property saleStartsAt 판매 시작 시각이자 신상순 정렬 기준. 미래 시각이면 예약 등록이다
 * @property saleEndsAt 판매 종료 시각. null이면 종료일이 없다
 * @property purchaseLimit 1인 구매 한도. null이면 무제한이고, 환불한 구매는 세지 않는다
 * @property salesCount 최근 판매량. Internal 배치가 갱신하고 판매량순 정렬과 인기 순위에 쓴다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "products")
class Product(
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    val category: RoomItemCategory,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "representative_item_id", nullable = false)
    val representativeItemId: Long,

    @Column(name = "price", nullable = false)
    val price: Int,

    @Column(name = "is_limited", nullable = false)
    var isLimited: Boolean,

    @Column(name = "sale_starts_at", nullable = false)
    var saleStartsAt: Instant,

    @Column(name = "sale_ends_at")
    var saleEndsAt: Instant? = null,

    @Column(name = "purchase_limit")
    var purchaseLimit: Int? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Column(name = "sales_count", nullable = false)
    var salesCount: Int = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    companion object {
        /**
         * 판매 기간 [saleStartsAt, saleEndsAt)이 비어 있지 않은지 확인한다. [saleEndsAt]이 null이면 종료일이 없다.
         *
         * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 종료 시각이 시작 시각과 같거나 앞인 경우
         */
        fun validateSalePeriod(saleStartsAt: Instant, saleEndsAt: Instant?) {
            if (saleEndsAt != null && !saleEndsAt.isAfter(saleStartsAt)) {
                throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE, "판매 종료 시각은 시작 시각보다 뒤여야 합니다.")
            }
        }
    }

    fun updateName(name: String) {
        this.name = name
    }

    fun updateIsLimited(isLimited: Boolean) {
        this.isLimited = isLimited
    }

    /**
     * 판매 기간을 바꾼다. 판매 중지는 [saleEndsAt]에 지금 시각을 넣는다.
     *
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 종료 시각이 시작 시각과 같거나 앞인 경우
     */
    fun updateSalePeriod(saleStartsAt: Instant, saleEndsAt: Instant?) {
        validateSalePeriod(saleStartsAt, saleEndsAt)
        this.saleStartsAt = saleStartsAt
        this.saleEndsAt = saleEndsAt
    }

    /**
     * 1인 구매 한도를 바꾼다. null이면 무제한이다.
     */
    fun updatePurchaseLimit(purchaseLimit: Int?) {
        this.purchaseLimit = purchaseLimit
    }
}
