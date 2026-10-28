package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.AdminProperties
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminPromotionResponse
import com.dandi.nyummy.admin.dto.AdminPromotionsResponse
import com.dandi.nyummy.admin.dto.CreatePromotionRequest
import com.dandi.nyummy.admin.dto.UpdatePromotionProductsRequest
import com.dandi.nyummy.admin.dto.UpdatePromotionRequest
import com.dandi.nyummy.admin.enum.AdminImagePurpose
import com.dandi.nyummy.admin.mapper.toAdminPromotionProductResponse
import com.dandi.nyummy.admin.mapper.toAdminPromotionResponse
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.entity.PromotionProduct
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.shop.repository.PromotionProductRepository
import com.dandi.nyummy.shop.repository.PromotionRepository
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 어드민의 프로모션 관리. 프로모션은 promotions, 진열 상품은 promotion_products에 있다.
 *
 * 구매 기록이 프로모션을 가리키므로 지우지 않는다. 끝낼 때는 종료 시각을 지금으로 바꾼다.
 */
@Service
@ConditionalOnAdminEnabled
class AdminPromotionService(
    private val promotionRepository: PromotionRepository,
    private val promotionProductRepository: PromotionProductRepository,
    private val productRepository: ProductRepository,
    private val adminProperties: AdminProperties,
) {

    /**
     * 프로모션을 최신순으로 조회한다. 끝난 프로모션도 포함하고, 진열 상품을 진열 순서대로 붙인다.
     */
    @Transactional(readOnly = true)
    fun getPromotions(): AdminPromotionsResponse {
        val promotions = promotionRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))
        val promotionProducts = promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(
            promotions.map {
                it.id
            },
        )
        val productsById = productRepository.findAllById(
            promotionProducts.map {
                it.productId
            }.toSet(),
        ).associateBy { it.id }
        val productIdsByPromotionId = promotionProducts.groupBy({ it.promotionId }, { it.productId })

        return AdminPromotionsResponse(
            promotions = promotions.map { promotion ->
                createResponse(promotion, productIdsByPromotionId[promotion.id].orEmpty().map(productsById::getValue))
            },
        )
    }

    /**
     * 프로모션을 만들고 [CreatePromotionRequest.productIds]를 그 순서대로 진열한다.
     *
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 상품이 겹치거나 종료 시각이 시작 시각보다 앞인 경우
     * @throws BusinessException [AdminErrorCode.PRODUCT_NOT_FOUND] 없는 상품이 있는 경우
     */
    @Transactional
    fun createPromotion(request: CreatePromotionRequest): AdminPromotionResponse {
        val products = getProductsInOrder(request.productIds)
        Promotion.validatePeriod(request.startsAt, request.endsAt)

        val promotion = promotionRepository.save(
            Promotion(
                name = request.name.trim(),
                category = request.category,
                discountRate = request.discountRate,
                startsAt = request.startsAt,
                endsAt = request.endsAt,
            ),
        )
        savePromotionProducts(promotion.id, products)

        return createResponse(promotion, products)
    }

    /**
     * 프로모션의 이름·할인율·기간을 받은 값으로 통째로 바꾼다(PUT). 대상은 바꾸지 않는다.
     *
     * @throws BusinessException [AdminErrorCode.PROMOTION_NOT_FOUND] 프로모션이 없는 경우
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 종료 시각이 시작 시각보다 앞인 경우
     */
    @Transactional
    fun updatePromotion(promotionId: Long, request: UpdatePromotionRequest): AdminPromotionResponse {
        val promotion = getPromotion(promotionId)

        promotion.updateName(request.name.trim())
        promotion.updateDiscountRate(request.discountRate)
        promotion.updatePeriod(request.startsAt, request.endsAt)

        val productIds = promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(listOf(promotionId))
            .map { it.productId }
        return createResponse(promotion, getProductsInOrder(productIds))
    }

    /**
     * 프로모션의 진열 상품을 [UpdatePromotionProductsRequest.productIds]로 통째로 바꾼다. 목록 순서가 진열 순서다.
     *
     * @throws BusinessException [AdminErrorCode.PROMOTION_NOT_FOUND] 프로모션이 없는 경우
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 상품이 겹치는 경우
     * @throws BusinessException [AdminErrorCode.PRODUCT_NOT_FOUND] 없는 상품이 있는 경우
     */
    @Transactional
    fun updatePromotionProducts(promotionId: Long, request: UpdatePromotionProductsRequest): AdminPromotionResponse {
        val promotion = getPromotion(promotionId)
        val products = getProductsInOrder(request.productIds)

        promotionProductRepository.deleteByPromotionId(promotionId)
        savePromotionProducts(promotionId, products)

        return createResponse(promotion, products)
    }

    private fun getPromotion(promotionId: Long): Promotion = promotionRepository.findByIdOrNull(promotionId)
        ?: throw BusinessException(AdminErrorCode.PROMOTION_NOT_FOUND)

    // 요청 순서를 지켜 상품을 돌려준다
    private fun getProductsInOrder(productIds: List<Long>): List<Product> {
        if (productIds.toSet().size != productIds.size) {
            throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE, "같은 상품을 프로모션에 두 번 넣을 수 없습니다.")
        }

        val productsById = productRepository.findAllById(productIds).associateBy { it.id }
        if (productsById.size != productIds.size) throw BusinessException(AdminErrorCode.PRODUCT_NOT_FOUND)

        return productIds.map(productsById::getValue)
    }

    private fun savePromotionProducts(promotionId: Long, products: List<Product>) {
        promotionProductRepository.saveAll(
            products.mapIndexed { index, product ->
                PromotionProduct(promotionId = promotionId, productId = product.id, displayOrder = index)
            },
        )
    }

    private fun createResponse(promotion: Promotion, products: List<Product>): AdminPromotionResponse =
        promotion.toAdminPromotionResponse(
            products = products.map {
                it.toAdminPromotionProductResponse(
                    imageUrl = AdminImagePurpose.ROOM_ITEM.createImageUrl(
                        adminProperties.cdnBaseUrl,
                        it.representativeItemId,
                    ),
                    discountRate = promotion.discountRate,
                )
            },
        )
}
