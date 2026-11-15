package com.dandi.nyummy.shop.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.language.pickTranslation
import com.dandi.nyummy.mail.repository.MailItemRepository
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.repository.OwnedRoomItemRepository
import com.dandi.nyummy.shop.calculator.calculatePurchasePrice
import com.dandi.nyummy.shop.config.ShopProperties
import com.dandi.nyummy.shop.dto.ProductAvailabilityResult
import com.dandi.nyummy.shop.dto.ShopItemResponse
import com.dandi.nyummy.shop.dto.TagResponse
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.enum.TagTone
import com.dandi.nyummy.shop.mapper.pickTagMessageKey
import com.dandi.nyummy.shop.mapper.toShopItemResponse
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRepository
import com.dandi.nyummy.shop.repository.ProductItemRepository
import com.dandi.nyummy.shop.repository.PromotionRepository
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.context.MessageSource
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
    private val itemPurchaseRepository: ItemPurchaseRepository,
    private val itemPurchaseProductRepository: ItemPurchaseProductRepository,
    private val mailItemRepository: MailItemRepository,
    private val shopProperties: ShopProperties,
    private val messageSource: MessageSource,
) {

    /**
     * [now]에 진행 중인 프로모션 중 사용자가 대상인 것.
     *
     * @throws BusinessException [UserErrorCode.USER_NOT_FOUND] 사용자가 없는 경우
     */
    fun getEligiblePromotions(userId: Long, now: Instant): List<Promotion> {
        val user = userRepository.findByIdOrNull(userId) ?: throw BusinessException(UserErrorCode.USER_NOT_FOUND)
        val hasPurchased = itemPurchaseRepository.existsByUserId(userId)

        return promotionRepository.findActivePromotions(now)
            .filter { it.category.isEligible(user.createdAt, hasPurchased, now, shopProperties.newUserTimeToLive) }
    }

    /**
     * 사용자가 상품을 지금 살 수 있는지 productId로 찾게 만든다. 장바구니와 구매가 같은 기준으로 막도록 여기서만 판정한다.
     *
     * 사유가 여럿이면 판매 기간 → 보유한 방 배경 → 1인 구매 한도 순서로 하나만 준다.
     * 방 배경이나 한도가 있는 상품이 없으면 그 쿼리는 나가지 않는다.
     */
    fun getProductAvailabilities(
        userId: Long,
        products: List<Product>,
        now: Instant,
    ): Map<Long, ProductAvailabilityResult> {
        val ownedBackgroundItemIds = getOwnedBackgroundItemIds(
            userId,
            products.filter { it.category == RoomItemCategory.BACKGROUND },
        )
        val purchasedCounts = getPurchasedCounts(userId, products.filter { it.purchaseLimit != null })

        return products.associate { product ->
            val remainingPurchaseLimit = product.purchaseLimit?.let { it - (purchasedCounts[product.id] ?: 0) }
            product.id to ProductAvailabilityResult(
                unavailableErrorCode = when {
                    !product.isOnSale(now) -> ShopErrorCode.PRODUCT_NOT_FOR_SALE

                    product.category == RoomItemCategory.BACKGROUND &&
                        product.representativeItemId in ownedBackgroundItemIds -> ShopErrorCode.ALREADY_OWNED

                    remainingPurchaseLimit != null && remainingPurchaseLimit <= 0 ->
                        ShopErrorCode.PURCHASE_LIMIT_EXCEEDED

                    else -> null
                },
                remainingPurchaseLimit = remainingPurchaseLimit,
            )
        }
    }

    // 방 배경은 단품이라 대표 아이템으로 본다. 사서 우편을 아직 안 받았어도 가진 것이다. 지운 아이템은 가진 것으로 보지 않는다.
    private fun getOwnedBackgroundItemIds(userId: Long, backgrounds: List<Product>): Set<Long> {
        if (backgrounds.isEmpty()) return emptySet()

        val itemIds = backgrounds.map { it.representativeItemId }
        return ownedRoomItemRepository.findOwnedRoomItemCounts(userId, itemIds).map { it.itemId }.toSet() +
            mailItemRepository.findUnclaimedPurchasedItemIds(userId, itemIds)
    }

    private fun getPurchasedCounts(userId: Long, limitedProducts: List<Product>): Map<Long, Int> {
        if (limitedProducts.isEmpty()) return emptyMap()

        return itemPurchaseProductRepository.findPurchasedProductCounts(userId, limitedProducts.map { it.id })
            .associate { it.productId to it.purchasedCount.toInt() }
    }

    // 아이템마다 보유 개수 + 우편함에서 아직 받지 않은 구매분
    private fun getOwnedCounts(userId: Long, itemIds: List<Long>): Map<Long, Int> {
        if (itemIds.isEmpty()) return emptyMap()

        val ownedCounts = ownedRoomItemRepository.findOwnedRoomItemCounts(userId, itemIds)
            .associate { it.itemId to it.ownedCount.toInt() }
        val unclaimedCounts = mailItemRepository.findUnclaimedPurchasedItemIds(userId, itemIds)
            .groupingBy { it }
            .eachCount()

        return itemIds.associateWith { (ownedCounts[it] ?: 0) + (unclaimedCounts[it] ?: 0) }
    }

    /**
     * 상품 카드를 productId로 찾게 만든다. 보유 개수는 단품만 세고(세트는 0), 우편함에서 아직 받지 않은 구매분도 더한다.
     * 보유 아이템과 안 받은 구매분을 각각 한 번의 쿼리로 센다.
     *
     * 분류 이름과 태그는 [language]로 꺼낸다. 상품 이름은 아직 번역하지 않는다.
     *
     * @param discountRates productId별 적용할 할인율. 없는 상품은 정가다
     * @param language 요청의 `X-Language`로 정한 앱 언어
     */
    fun createShopItems(
        userId: Long,
        products: List<Product>,
        discountRates: Map<Long, Int>,
        now: Instant,
        language: Language,
    ): Map<Long, ShopItemResponse> {
        if (products.isEmpty()) return emptyMap()

        val itemCounts = productItemRepository.findByProductIdIn(products.map { it.id })
            .groupingBy { it.productId }
            .eachCount()
        val singleProducts = products.filter { itemCounts[it.id] == 1 }
        val ownedCounts = getOwnedCounts(userId, singleProducts.map { it.representativeItemId })
        val newProductStartsAt = now.minus(shopProperties.newProductTimeToLive)

        return products.associate { product ->
            val ownedCount = if (itemCounts[product.id] == 1) ownedCounts[product.representativeItemId] ?: 0 else 0
            val purchasePrice = calculatePurchasePrice(product.price, discountRates[product.id])
            val tagMessageKey = pickTagMessageKey(
                isLimited = product.isLimited,
                isDiscounted = purchasePrice < product.price,
                isNew = product.saleStartsAt.isAfter(newProductStartsAt),
            )

            product.id to product.toShopItemResponse(
                name = product.nameTranslations.pickTranslation(language, product.name),
                purchasePrice = purchasePrice,
                ownedCount = ownedCount,
                categoryName = messageSource.getMessage("roomItemCategory.${product.category.name}", language),
                tag = tagMessageKey?.let { TagResponse(messageSource.getMessage(it, language), TagTone.PROMO) },
            )
        }
    }
}
