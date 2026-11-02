package com.dandi.nyummy.shop.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.room.repository.OwnedRoomItemRepository
import com.dandi.nyummy.shop.calculator.calculatePurchasePrice
import com.dandi.nyummy.shop.config.ShopProperties
import com.dandi.nyummy.shop.dto.ShopItemResponse
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.mapper.toShopItemResponse
import com.dandi.nyummy.shop.repository.ProductItemRepository
import com.dandi.nyummy.shop.repository.PromotionRepository
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 상품 카드([ShopItemResponse])와 그 가격을 만든다. 추천 탭·상품 목록·프로모션 화면·장바구니가 같은 규칙으로
 * 가격·태그·보유 개수를 보이도록 여기서만 만든다.
 *
 * 가격은 상품이 든 프로모션 중 진행 중이고 사용자가 대상인 것의 할인율 가장 큰 것으로 계산한다.
 * 조회만 하므로 트랜잭션은 부르는 쪽 것을 쓴다.
 */
@Service
class ShopItemService(
    private val userRepository: UserRepository,
    private val promotionRepository: PromotionRepository,
    private val productItemRepository: ProductItemRepository,
    private val ownedRoomItemRepository: OwnedRoomItemRepository,
    private val shopProperties: ShopProperties,
) {

    /**
     * [now]에 진행 중인 프로모션 중 사용자가 대상인 것.
     *
     * @throws BusinessException [UserErrorCode.USER_NOT_FOUND] 사용자가 없는 경우
     */
    fun getEligiblePromotions(userId: Long, now: Instant): List<Promotion> {
        val user = userRepository.findByIdOrNull(userId) ?: throw BusinessException(UserErrorCode.USER_NOT_FOUND)

        return promotionRepository.findActivePromotions(now)
            .filter { it.category.isEligible(user.createdAt, now, shopProperties.newUserTimeToLive) }
    }

    /**
     * 상품 카드를 productId로 찾게 만든다. 보유 개수는 단품만 세고(세트는 0), 한 번의 쿼리로 센다.
     *
     * @param discountRates productId별 적용할 할인율. 없는 상품은 정가다
     */
    fun createShopItems(
        userId: Long,
        products: List<Product>,
        discountRates: Map<Long, Int>,
        now: Instant,
    ): Map<Long, ShopItemResponse> {
        if (products.isEmpty()) return emptyMap()

        val itemCounts = productItemRepository.findByProductIdIn(products.map { it.id })
            .groupingBy { it.productId }
            .eachCount()
        val singleProducts = products.filter { itemCounts[it.id] == 1 }
        val ownedCounts = if (singleProducts.isEmpty()) {
            emptyMap()
        } else {
            ownedRoomItemRepository.findOwnedRoomItemCounts(userId, singleProducts.map { it.representativeItemId })
                .associate { it.itemId to it.ownedCount.toInt() }
        }
        val newProductStartsAt = now.minus(shopProperties.newProductTimeToLive)

        return products.associate { product ->
            val ownedCount = if (itemCounts[product.id] == 1) ownedCounts[product.representativeItemId] ?: 0 else 0
            product.id to product.toShopItemResponse(
                purchasePrice = calculatePurchasePrice(product.price, discountRates[product.id]),
                isNew = product.saleStartsAt.isAfter(newProductStartsAt),
                ownedCount = ownedCount,
            )
        }
    }
}
