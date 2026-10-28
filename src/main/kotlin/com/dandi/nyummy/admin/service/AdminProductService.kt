package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.AdminProperties
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminProductItemResponse
import com.dandi.nyummy.admin.dto.AdminProductResponse
import com.dandi.nyummy.admin.dto.AdminProductsResponse
import com.dandi.nyummy.admin.dto.CreateProductRequest
import com.dandi.nyummy.admin.dto.UpdateProductRequest
import com.dandi.nyummy.admin.enum.AdminImagePurpose
import com.dandi.nyummy.admin.mapper.toAdminProductResponse
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.item.repository.ItemRepository
import com.dandi.nyummy.room.entity.RoomItem
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.repository.RoomItemRepository
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.ProductItem
import com.dandi.nyummy.shop.repository.ProductItemRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 어드민의 상품(판매 단위) 관리. 상품은 products, 구성품은 product_items에 있다.
 *
 * 구매·환불 기록이 상품을 가리키므로 지우지 않는다. 판매를 멈출 때는 판매 종료 시각을 지금으로 바꾼다.
 */
@Service
@ConditionalOnAdminEnabled
class AdminProductService(
    private val productRepository: ProductRepository,
    private val productItemRepository: ProductItemRepository,
    private val roomItemRepository: RoomItemRepository,
    private val itemRepository: ItemRepository,
    private val adminProperties: AdminProperties,
) {

    /**
     * 상품을 최신순으로 조회한다. [category]가 null이면 모든 분류를 조회한다. 판매가 끝난 상품도 포함한다.
     */
    @Transactional(readOnly = true)
    fun getProducts(category: RoomItemCategory?): AdminProductsResponse {
        val products = productRepository.findProducts(category)
        val productItems = productItemRepository.findByProductIdIn(products.map { it.id })
        val itemNames = getItemNames(productItems.map { it.itemId })
        val productItemsByProductId = productItems.groupBy { it.productId }

        return AdminProductsResponse(
            products = products.map { product ->
                createResponse(product, productItemsByProductId[product.id].orEmpty(), itemNames)
            },
        )
    }

    /**
     * 상품과 구성품을 만든다. 구성품이 하나면 단품, 여럿이면 세트다.
     *
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 구성품이 겹치거나, 대표 구성품이 구성품에 없거나,
     *   단품의 분류·정가가 구성품과 다르거나, 세트에 방 배경이 있거나, 세트 정가가 구성품 정가 합보다 크거나,
     *   판매 종료 시각이 시작 시각보다 앞인 경우
     * @throws BusinessException [AdminErrorCode.ROOM_ITEM_NOT_FOUND] 마이룸 아이템이 아닌 구성품이 있는 경우
     */
    @Transactional
    fun createProduct(request: CreateProductRequest): AdminProductResponse {
        val itemIds = request.items.map { it.itemId }
        if (itemIds.toSet().size != itemIds.size) throw createInvalidInputException("같은 아이템을 구성품에 두 번 넣을 수 없습니다.")
        if (request.representativeItemId !in itemIds) throw createInvalidInputException("대표 구성품은 구성품 중 하나여야 합니다.")

        val roomItems = roomItemRepository.findAllById(itemIds)
        if (roomItems.size != itemIds.size) throw BusinessException(AdminErrorCode.ROOM_ITEM_NOT_FOUND)

        if (request.items.size == 1) {
            validateSingleProduct(request, roomItems.single())
        } else {
            validateSetProduct(request, roomItems)
        }
        Product.validateSalePeriod(request.saleStartsAt, request.saleEndsAt)

        val product = productRepository.save(
            Product(
                category = request.category,
                name = request.name.trim(),
                representativeItemId = request.representativeItemId,
                price = request.price,
                isLimited = request.isLimited,
                saleStartsAt = request.saleStartsAt,
                saleEndsAt = request.saleEndsAt,
                purchaseLimit = request.purchaseLimit,
            ),
        )
        val productItems = productItemRepository.saveAll(
            request.items.map { ProductItem(productId = product.id, itemId = it.itemId, listPrice = it.listPrice) },
        )

        return createResponse(product, productItems, getItemNames(itemIds))
    }

    /**
     * 상품의 이름·기간 한정·판매 기간·1인 구매 한도를 받은 값으로 통째로 바꾼다(PUT).
     * 정가·분류·구성품·대표 구성품은 바꾸지 않는다. 정가가 달라지면 판매를 멈추고 새 상품을 만든다.
     *
     * @throws BusinessException [AdminErrorCode.PRODUCT_NOT_FOUND] 상품이 없는 경우
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 판매 종료 시각이 시작 시각보다 앞인 경우
     */
    @Transactional
    fun updateProduct(productId: Long, request: UpdateProductRequest): AdminProductResponse {
        val product = productRepository.findByIdOrNull(productId)
            ?: throw BusinessException(AdminErrorCode.PRODUCT_NOT_FOUND)

        product.updateName(request.name.trim())
        product.updateIsLimited(request.isLimited)
        product.updateSalePeriod(request.saleStartsAt, request.saleEndsAt)
        product.updatePurchaseLimit(request.purchaseLimit)

        val productItems = productItemRepository.findByProductIdIn(listOf(productId))
        return createResponse(product, productItems, getItemNames(productItems.map { it.itemId }))
    }

    // 단품은 분류와 정가가 구성품 하나와 같다
    private fun validateSingleProduct(request: CreateProductRequest, roomItem: RoomItem) {
        if (request.category != roomItem.category) throw createInvalidInputException("단품의 분류는 아이템의 분류와 같아야 합니다.")
        if (request.items.single().listPrice != request.price) {
            throw createInvalidInputException("단품의 구성품 정가는 상품 정가와 같아야 합니다.")
        }
    }

    // 방 배경은 보유 여부로 담기·구매를 막으므로 단품으로만 판다. 세트가 낱개 정가 합보다 비싸면 세트로 팔 이유가 없다.
    private fun validateSetProduct(request: CreateProductRequest, roomItems: List<RoomItem>) {
        if (request.category == RoomItemCategory.BACKGROUND ||
            roomItems.any { it.category == RoomItemCategory.BACKGROUND }
        ) {
            throw createInvalidInputException("방 배경은 단품으로만 팔 수 있습니다.")
        }
        if (request.price > request.items.sumOf { it.listPrice }) {
            throw createInvalidInputException("세트 정가는 구성품 정가 합보다 클 수 없습니다.")
        }
    }

    private fun createInvalidInputException(message: String) =
        BusinessException(CommonErrorCode.INVALID_INPUT_VALUE, message)

    private fun getItemNames(itemIds: Collection<Long>): Map<Long, String> =
        itemRepository.findAllById(itemIds.toSet()).associate { it.id to it.name }

    private fun createResponse(
        product: Product,
        productItems: List<ProductItem>,
        itemNames: Map<Long, String>,
    ): AdminProductResponse = product.toAdminProductResponse(
        imageUrl = createImageUrl(product.representativeItemId),
        items = productItems.sortedBy { it.itemId }.map {
            AdminProductItemResponse(
                itemId = it.itemId,
                name = itemNames.getValue(it.itemId),
                listPrice = it.listPrice,
                imageUrl = createImageUrl(it.itemId),
            )
        },
    )

    private fun createImageUrl(itemId: Long): String =
        AdminImagePurpose.ROOM_ITEM.createImageUrl(adminProperties.cdnBaseUrl, itemId)
}
