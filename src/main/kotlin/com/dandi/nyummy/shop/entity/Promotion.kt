package com.dandi.nyummy.shop.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.shop.enum.PromotionCategory
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
 * 프로모션. 기간과 대상을 정해 상품([PromotionProduct])을 묶어 판다. [id]가 앱 링크 /shop/promotions/{promotionId}의 값이다.
 *
 * 구매 기록이 프로모션을 가리키므로 지우지 않고, 끝낼 때는 [endsAt]에 지금 시각을 넣는다.
 *
 * @property category 대상. 바뀌면 프로모션별 구매 통계의 뜻이 달라지므로 바꾸지 않는다
 * @property discountRate 할인율(0~100 정수 퍼센트). null이면 할인 없이 상품만 묶는다.
 *   가격은 진행 중이고 사용자가 대상인 프로모션 중 할인율이 가장 큰 것 하나만 적용한다
 * @property endsAt 종료 시각. null이면 종료일이 없다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "promotions")
class Promotion(
    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    val category: PromotionCategory,

    @Column(name = "discount_rate")
    var discountRate: Int? = null,

    @Column(name = "starts_at", nullable = false)
    var startsAt: Instant,

    @Column(name = "ends_at")
    var endsAt: Instant? = null,
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
         * 기간 [startsAt, endsAt)이 비어 있지 않은지 확인한다. [endsAt]이 null이면 종료일이 없다.
         *
         * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 종료 시각이 시작 시각과 같거나 앞인 경우
         */
        fun validatePeriod(startsAt: Instant, endsAt: Instant?) {
            if (endsAt != null && !endsAt.isAfter(startsAt)) {
                throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE, "프로모션 종료 시각은 시작 시각보다 뒤여야 합니다.")
            }
        }
    }

    fun updateName(name: String) {
        this.name = name
    }

    /**
     * 할인율을 바꾼다. null이면 할인 없이 상품만 묶는다. 장바구니에 담긴 상품의 결제가도 바로 바뀐다.
     */
    fun updateDiscountRate(discountRate: Int?) {
        this.discountRate = discountRate
    }

    /**
     * 기간을 바꾼다. 프로모션을 끝낼 때는 [endsAt]에 지금 시각을 넣는다.
     *
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 종료 시각이 시작 시각과 같거나 앞인 경우
     */
    fun updatePeriod(startsAt: Instant, endsAt: Instant?) {
        validatePeriod(startsAt, endsAt)
        this.startsAt = startsAt
        this.endsAt = endsAt
    }
}
