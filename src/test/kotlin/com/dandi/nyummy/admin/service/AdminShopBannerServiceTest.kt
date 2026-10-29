package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.AdminProperties
import com.dandi.nyummy.admin.dto.CreateShopBannerRequest
import com.dandi.nyummy.admin.dto.UpdateShopBannerRequest
import com.dandi.nyummy.admin.validator.AdminImageValidator
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.infra.storage.s3.S3StorageClient
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.entity.ShopBanner
import com.dandi.nyummy.shop.enum.PromotionCategory
import com.dandi.nyummy.shop.repository.PromotionRepository
import com.dandi.nyummy.shop.repository.ShopBannerRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminShopBannerServiceTest {

    private val startsAt = Instant.parse("2026-11-01T00:00:00Z")
    private val endsAt = Instant.parse("2026-11-15T00:00:00Z")
    private val adminId = 1L
    private val otherAdminId = 2L
    private val promotionId = 3L
    private val bannerId = 5L
    private val oldImageKey = "shop-banners/old.png"

    private val shopBannerRepository = mockk<ShopBannerRepository>()
    private val promotionRepository = mockk<PromotionRepository>()
    private val adminImageValidator = mockk<AdminImageValidator>()
    private val s3StorageClient = mockk<S3StorageClient>()

    private val adminShopBannerService = AdminShopBannerService(
        shopBannerRepository = shopBannerRepository,
        promotionRepository = promotionRepository,
        adminImageValidator = adminImageValidator,
        s3StorageClient = s3StorageClient,
        adminProperties = AdminProperties(cdnBaseUrl = "https://cdn.test.com"),
    )

    private val image = MockMultipartFile("image", "banner.png", "image/png", byteArrayOf(1, 2, 3))
    private val uploadedKey = slot<String>()
    private val savedBanner = slot<ShopBanner>()

    init {
        every { adminImageValidator.validateImage(any(), any()) } just Runs
        coEvery { s3StorageClient.uploadObject(capture(uploadedKey), any(), any(), any()) } just Runs
        every { promotionRepository.findByIdOrNull(promotionId) } returns promotion()
        every { shopBannerRepository.save(capture(savedBanner)) } answers {
            firstArg<ShopBanner>().also { ReflectionTestUtils.setField(it, "id", bannerId) }
        }
    }

    private fun promotion() = Promotion(
        name = "집사 첫 장보기 30%",
        category = PromotionCategory.FIRST_PURCHASE,
        discountRate = 30,
        startsAt = startsAt,
    ).also { ReflectionTestUtils.setField(it, "id", promotionId) }

    private fun shopBanner() = ShopBanner(
        promotionId = promotionId,
        imageKey = oldImageKey,
        title = "첫 구매 혜택",
        description = "모든 상품 30% 할인",
        titleColor = "contentOnInverse",
        descriptionColor = "contentOnInverse",
        displayOrder = 0,
        startsAt = startsAt,
        endsAt = endsAt,
        adminId = otherAdminId,
    ).also { ReflectionTestUtils.setField(it, "id", bannerId) }

    private fun createRequest(
        promotionId: Long = this.promotionId,
        description: String? = "모든 상품 30% 할인",
        endsAt: Instant? = this.endsAt,
    ) = CreateShopBannerRequest(
        promotionId = promotionId,
        title = "  첫 구매 혜택  ",
        description = description,
        titleColor = "contentOnInverse",
        descriptionColor = "contentSecondary",
        displayOrder = 1,
        startsAt = startsAt,
        endsAt = endsAt,
    )

    private fun updateRequest(description: String? = "이번 주까지", endsAt: Instant? = null) = UpdateShopBannerRequest(
        title = "첫 구매 혜택 마감 임박",
        description = description,
        titleColor = "contentPrimary",
        descriptionColor = "contentSecondary",
        displayOrder = 2,
        startsAt = startsAt,
        endsAt = endsAt,
    )

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    @Test
    fun `배너 목록은 프로모션 이름과 CDN 이미지 주소를 붙여 최신순으로 준다`() {
        every { shopBannerRepository.findAll(Sort.by(Sort.Direction.DESC, "id")) } returns listOf(shopBanner())
        every { promotionRepository.findAllById(setOf(promotionId)) } returns listOf(promotion())

        val response = adminShopBannerService.getShopBanners()

        val banner = response.shopBanners.single()
        assertEquals("집사 첫 장보기 30%", banner.promotionName)
        assertEquals("https://cdn.test.com/$oldImageKey", banner.imageUrl)
    }

    @Test
    fun `배너를 만들면 이미지를 새 키에 올리고 그 키와 만든 운영자를 저장한다`() {
        val response = adminShopBannerService.createShopBanner(adminId, createRequest(), image)

        assertTrue(uploadedKey.captured.startsWith("shop-banners/"))
        assertTrue(uploadedKey.captured.endsWith(".png"))
        assertEquals(uploadedKey.captured, savedBanner.captured.imageKey)
        assertEquals("첫 구매 혜택", savedBanner.captured.title)
        assertEquals(adminId, savedBanner.captured.adminId)
        assertEquals(bannerId, response.bannerId)
        assertEquals("https://cdn.test.com/${uploadedKey.captured}", response.imageUrl)
    }

    @Test
    fun `설명이 공백뿐이면 설명과 설명 색을 저장하지 않는다`() {
        adminShopBannerService.createShopBanner(adminId, createRequest(description = "   "), image)

        assertNull(savedBanner.captured.description)
        assertNull(savedBanner.captured.descriptionColor)
    }

    @Test
    fun `없는 프로모션이면 거부하고 이미지를 올리지 않는다`() {
        every { promotionRepository.findByIdOrNull(99L) } returns null

        assertBusinessException(AdminErrorCode.PROMOTION_NOT_FOUND) {
            adminShopBannerService.createShopBanner(adminId, createRequest(promotionId = 99L), image)
        }
        coVerify(exactly = 0) { s3StorageClient.uploadObject(any(), any(), any(), any()) }
        verify(exactly = 0) { shopBannerRepository.save(any()) }
    }

    @Test
    fun `종료 시각이 시작 시각과 같으면 거부하고 이미지를 올리지 않는다`() {
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminShopBannerService.createShopBanner(adminId, createRequest(endsAt = startsAt), image)
        }
        coVerify(exactly = 0) { s3StorageClient.uploadObject(any(), any(), any(), any()) }
    }

    @Test
    fun `이미지 업로드에 실패하면 배너를 만들지 않는다`() {
        coEvery { s3StorageClient.uploadObject(any(), any(), any(), any()) } throws
            BusinessException(S3ErrorCode.OBJECT_UPLOAD_FAILED)

        assertBusinessException(S3ErrorCode.OBJECT_UPLOAD_FAILED) {
            adminShopBannerService.createShopBanner(adminId, createRequest(), image)
        }
        verify(exactly = 0) { shopBannerRepository.save(any()) }
    }

    @Test
    fun `배너를 수정하면 글자·순서·기간과 마지막으로 바꾼 운영자가 바뀌고 이미지는 그대로다`() {
        val shopBanner = shopBanner()
        every { shopBannerRepository.findByIdOrNull(bannerId) } returns shopBanner

        adminShopBannerService.updateShopBanner(adminId, bannerId, updateRequest())

        assertEquals("첫 구매 혜택 마감 임박", shopBanner.title)
        assertEquals("이번 주까지", shopBanner.description)
        assertEquals("contentPrimary", shopBanner.titleColor)
        assertEquals(2, shopBanner.displayOrder)
        assertNull(shopBanner.endsAt)
        assertEquals(adminId, shopBanner.adminId)
        assertEquals(oldImageKey, shopBanner.imageKey)
    }

    @Test
    fun `설명을 비우면 설명 색도 지운다`() {
        val shopBanner = shopBanner()
        every { shopBannerRepository.findByIdOrNull(bannerId) } returns shopBanner

        adminShopBannerService.updateShopBanner(adminId, bannerId, updateRequest(description = null))

        assertNull(shopBanner.description)
        assertNull(shopBanner.descriptionColor)
    }

    @Test
    fun `없는 배너를 수정하면 거부한다`() {
        every { shopBannerRepository.findByIdOrNull(bannerId) } returns null

        assertBusinessException(AdminErrorCode.SHOP_BANNER_NOT_FOUND) {
            adminShopBannerService.updateShopBanner(adminId, bannerId, updateRequest())
        }
    }

    @Test
    fun `종료 시각이 시작 시각보다 앞이면 수정을 거부하고 아무것도 바꾸지 않는다`() {
        val shopBanner = shopBanner()
        every { shopBannerRepository.findByIdOrNull(bannerId) } returns shopBanner

        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminShopBannerService.updateShopBanner(adminId, bannerId, updateRequest(endsAt = startsAt.minusSeconds(1)))
        }
        assertEquals("첫 구매 혜택", shopBanner.title)
        assertEquals(otherAdminId, shopBanner.adminId)
    }

    @Test
    fun `이미지를 교체하면 새 키에 올리고 배너가 새 키를 가리킨다`() {
        every { shopBannerRepository.findByIdOrNull(bannerId) } returns shopBanner()

        val response = adminShopBannerService.updateShopBannerImage(adminId, bannerId, image)

        assertNotEquals(oldImageKey, uploadedKey.captured)
        assertEquals(uploadedKey.captured, savedBanner.captured.imageKey)
        assertEquals(adminId, savedBanner.captured.adminId)
        assertEquals("https://cdn.test.com/${uploadedKey.captured}", response.imageUrl)
    }

    @Test
    fun `이미지 교체 업로드에 실패하면 배너를 저장하지 않는다`() {
        every { shopBannerRepository.findByIdOrNull(bannerId) } returns shopBanner()
        coEvery { s3StorageClient.uploadObject(any(), any(), any(), any()) } throws
            BusinessException(S3ErrorCode.OBJECT_UPLOAD_FAILED)

        assertBusinessException(S3ErrorCode.OBJECT_UPLOAD_FAILED) {
            adminShopBannerService.updateShopBannerImage(adminId, bannerId, image)
        }
        verify(exactly = 0) { shopBannerRepository.save(any()) }
    }
}
