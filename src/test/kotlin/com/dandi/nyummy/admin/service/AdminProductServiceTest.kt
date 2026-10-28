package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.AdminProperties
import com.dandi.nyummy.admin.dto.CreateProductItemRequest
import com.dandi.nyummy.admin.dto.CreateProductRequest
import com.dandi.nyummy.admin.dto.UpdateProductRequest
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.item.entity.Item
import com.dandi.nyummy.item.repository.ItemRepository
import com.dandi.nyummy.room.entity.RoomItem
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.repository.RoomItemRepository
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.ProductItem
import com.dandi.nyummy.shop.repository.ProductItemRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminProductServiceTest {

    private val saleStartsAt = Instant.parse("2026-11-01T00:00:00Z")
    private val saleEndsAt = Instant.parse("2026-12-01T00:00:00Z")
    private val productId = 101L

    private val productRepository = mockk<ProductRepository>()
    private val productItemRepository = mockk<ProductItemRepository>()
    private val roomItemRepository = mockk<RoomItemRepository>()
    private val itemRepository = mockk<ItemRepository>()

    private val adminProductService = AdminProductService(
        productRepository = productRepository,
        productItemRepository = productItemRepository,
        roomItemRepository = roomItemRepository,
        itemRepository = itemRepository,
        adminProperties = AdminProperties(cdnBaseUrl = "https://cdn.test.com"),
    )

    private val roomItems = listOf(
        RoomItem(itemId = 11, category = RoomItemCategory.BED),
        RoomItem(itemId = 21, category = RoomItemCategory.ETC),
        RoomItem(itemId = 22, category = RoomItemCategory.ETC),
        RoomItem(itemId = 31, category = RoomItemCategory.BACKGROUND),
    ).associateBy { it.itemId }

    private val savedProduct = slot<Product>()
    private val savedProductItems = slot<List<ProductItem>>()

    init {
        every { roomItemRepository.findAllById(any()) } answers {
            firstArg<Iterable<Long>>().mapNotNull { roomItems[it] }
        }
        every { itemRepository.findAllById(any()) } answers {
            firstArg<Iterable<Long>>().map { item(it, "아이템 $it") }
        }
        every { productRepository.save(capture(savedProduct)) } answers {
            firstArg<Product>().also { ReflectionTestUtils.setField(it, "id", productId) }
        }
        every { productItemRepository.saveAll(capture(savedProductItems)) } answers { firstArg() }
    }

    private fun item(id: Long, name: String) = Item(name = name).also { ReflectionTestUtils.setField(it, "id", id) }

    private fun product(saleEndsAt: Instant? = null) = Product(
        category = RoomItemCategory.BED,
        name = "돔 침대",
        representativeItemId = 11,
        price = 650,
        isLimited = false,
        saleStartsAt = saleStartsAt,
        saleEndsAt = saleEndsAt,
    ).also { ReflectionTestUtils.setField(it, "id", productId) }

    private fun singleRequest(
        category: RoomItemCategory = RoomItemCategory.BED,
        price: Int = 650,
        listPrice: Int = 650,
        saleEndsAt: Instant? = null,
    ) = CreateProductRequest(
        name = "  돔 침대  ",
        category = category,
        representativeItemId = 11,
        price = price,
        isLimited = false,
        saleStartsAt = saleStartsAt,
        saleEndsAt = saleEndsAt,
        purchaseLimit = null,
        items = listOf(CreateProductItemRequest(itemId = 11, listPrice = listPrice)),
    )

    private fun setRequest(
        category: RoomItemCategory = RoomItemCategory.ETC,
        price: Int = 1000,
        items: List<CreateProductItemRequest> = listOf(
            CreateProductItemRequest(itemId = 21, listPrice = 600),
            CreateProductItemRequest(itemId = 22, listPrice = 500),
        ),
        representativeItemId: Long = 21,
    ) = CreateProductRequest(
        name = "소품 세트",
        category = category,
        representativeItemId = representativeItemId,
        price = price,
        isLimited = true,
        saleStartsAt = saleStartsAt,
        saleEndsAt = saleEndsAt,
        purchaseLimit = 1,
        items = items,
    )

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    private fun assertNothingSaved() {
        verify(exactly = 0) { productRepository.save(any()) }
        verify(exactly = 0) { productItemRepository.saveAll(any<List<ProductItem>>()) }
    }

    // createProduct

    @Test
    fun `단품을 만들면 이름을 다듬어 저장하고 구성품 하나와 대표 그림 주소를 돌려준다`() {
        val response = adminProductService.createProduct(singleRequest())

        assertEquals("돔 침대", savedProduct.captured.name)
        assertEquals(productId, response.productId)
        assertEquals("https://cdn.test.com/items/11.png", response.imageUrl)
        assertEquals(listOf(11L), response.items.map { it.itemId })
        assertEquals(650, response.items.single().listPrice)
        val savedItem = savedProductItems.captured.single()
        assertEquals(Triple(productId, 11L, 650), Triple(savedItem.productId, savedItem.itemId, savedItem.listPrice))
    }

    @Test
    fun `세트를 만들면 구성품마다 정가를 저장한다`() {
        val response = adminProductService.createProduct(setRequest())

        assertEquals(1000, response.price)
        assertEquals(listOf(21L to 600, 22L to 500), response.items.map { it.itemId to it.listPrice })
        assertEquals(1, response.purchaseLimit)
    }

    @Test
    fun `세트 정가가 구성품 정가 합과 같으면 만든다`() {
        val response = adminProductService.createProduct(setRequest(price = 1100))

        assertEquals(1100, response.price)
    }

    @Test
    fun `같은 아이템을 구성품에 두 번 넣으면 거부한다`() {
        val items = listOf(CreateProductItemRequest(21, 600), CreateProductItemRequest(21, 600))

        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminProductService.createProduct(setRequest(items = items))
        }
        assertNothingSaved()
    }

    @Test
    fun `대표 구성품이 구성품에 없으면 거부한다`() {
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminProductService.createProduct(setRequest(representativeItemId = 11))
        }
        assertNothingSaved()
    }

    @Test
    fun `마이룸 아이템이 아닌 구성품이 있으면 거부한다`() {
        val items = listOf(CreateProductItemRequest(21, 600), CreateProductItemRequest(99, 500))

        assertBusinessException(AdminErrorCode.ROOM_ITEM_NOT_FOUND) {
            adminProductService.createProduct(setRequest(items = items))
        }
        assertNothingSaved()
    }

    @Test
    fun `단품의 분류가 아이템 분류와 다르면 거부한다`() {
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminProductService.createProduct(singleRequest(category = RoomItemCategory.ETC))
        }
        assertNothingSaved()
    }

    @Test
    fun `단품의 구성품 정가가 상품 정가와 다르면 거부한다`() {
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminProductService.createProduct(singleRequest(price = 650, listPrice = 700))
        }
        assertNothingSaved()
    }

    @Test
    fun `방 배경이 든 세트는 거부한다`() {
        val items = listOf(CreateProductItemRequest(21, 600), CreateProductItemRequest(31, 1000))

        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminProductService.createProduct(setRequest(items = items))
        }
        assertNothingSaved()
    }

    @Test
    fun `분류가 방 배경인 세트는 거부한다`() {
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminProductService.createProduct(setRequest(category = RoomItemCategory.BACKGROUND))
        }
        assertNothingSaved()
    }

    @Test
    fun `세트 정가가 구성품 정가 합보다 크면 거부한다`() {
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminProductService.createProduct(setRequest(price = 1101))
        }
        assertNothingSaved()
    }

    @Test
    fun `판매 종료 시각이 시작 시각보다 앞이면 거부한다`() {
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminProductService.createProduct(singleRequest(saleEndsAt = saleStartsAt.minusSeconds(1)))
        }
        assertNothingSaved()
    }

    // updateProduct

    @Test
    fun `상품을 수정하면 이름을 다듬고 판매 기간과 한도를 보낸 값으로 바꾼다`() {
        val product = product(saleEndsAt = saleEndsAt)
        every { productRepository.findByIdOrNull(productId) } returns product
        every { productItemRepository.findByProductIdIn(listOf(productId)) } returns
            listOf(ProductItem(productId, 11, 650))

        val response = adminProductService.updateProduct(
            productId,
            UpdateProductRequest(
                name = " 새 이름 ",
                isLimited = true,
                saleStartsAt = saleStartsAt,
                saleEndsAt = null,
                purchaseLimit = 3,
            ),
        )

        assertEquals("새 이름", product.name)
        assertTrue(product.isLimited)
        assertNull(product.saleEndsAt)
        assertEquals(3, product.purchaseLimit)
        assertEquals("새 이름", response.name)
        assertEquals(listOf(11L), response.items.map { it.itemId })
    }

    @Test
    fun `없는 상품을 수정하면 거부한다`() {
        every { productRepository.findByIdOrNull(productId) } returns null

        assertBusinessException(AdminErrorCode.PRODUCT_NOT_FOUND) {
            adminProductService.updateProduct(
                productId,
                UpdateProductRequest(
                    "이름",
                    isLimited = false,
                    saleStartsAt = saleStartsAt,
                    saleEndsAt = null,
                    purchaseLimit = null,
                ),
            )
        }
    }

    // getProducts

    @Test
    fun `상품 목록은 상품마다 자기 구성품을 itemId 순서로 붙인다`() {
        val set = Product(
            category = RoomItemCategory.ETC,
            name = "소품 세트",
            representativeItemId = 22,
            price = 1000,
            isLimited = false,
            saleStartsAt = saleStartsAt,
        ).also { ReflectionTestUtils.setField(it, "id", 102L) }
        every { productRepository.findProducts(null) } returns listOf(set, product())
        every { productItemRepository.findByProductIdIn(listOf(102L, productId)) } returns listOf(
            ProductItem(102L, 22, 500),
            ProductItem(productId, 11, 650),
            ProductItem(102L, 21, 600),
        )

        val response = adminProductService.getProducts(null)

        assertEquals(listOf(102L, productId), response.products.map { it.productId })
        assertEquals(listOf(21L, 22L), response.products[0].items.map { it.itemId })
        assertEquals("https://cdn.test.com/items/22.png", response.products[0].imageUrl)
        assertEquals(listOf(11L), response.products[1].items.map { it.itemId })
    }
}
