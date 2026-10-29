package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.AdminProperties
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminShopBannerResponse
import com.dandi.nyummy.admin.dto.AdminShopBannersResponse
import com.dandi.nyummy.admin.dto.CreateShopBannerRequest
import com.dandi.nyummy.admin.dto.UpdateShopBannerRequest
import com.dandi.nyummy.admin.enum.AdminImagePurpose
import com.dandi.nyummy.admin.mapper.toAdminShopBannerResponse
import com.dandi.nyummy.admin.validator.AdminImageValidator
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.infra.storage.s3.S3StorageClient
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.entity.ShopBanner
import com.dandi.nyummy.shop.repository.PromotionRepository
import com.dandi.nyummy.shop.repository.ShopBannerRepository
import kotlinx.coroutines.runBlocking
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

/**
 * 어드민의 상점 배너 관리. 배너는 shop_banners에 있고, 추천 탭 맨 위에서 프로모션 하나를 알린다.
 *
 * 이미지는 올릴 때마다 `shop-banners/{임의 이름}.png` 새 키에 두고 그 키를 저장한다([AdminImagePurpose.SHOP_BANNER]).
 * 지우지 않고, 내릴 때는 종료 시각을 지금으로 바꾼다.
 */
@Service
@ConditionalOnAdminEnabled
class AdminShopBannerService(
    private val shopBannerRepository: ShopBannerRepository,
    private val promotionRepository: PromotionRepository,
    private val adminImageValidator: AdminImageValidator,
    private val s3StorageClient: S3StorageClient,
    private val adminProperties: AdminProperties,
) {

    companion object {
        private val IMAGE_PURPOSE = AdminImagePurpose.SHOP_BANNER
    }

    /**
     * 배너를 최신순으로 조회한다. 내린 배너도 포함한다.
     */
    @Transactional(readOnly = true)
    fun getShopBanners(): AdminShopBannersResponse {
        val shopBanners = shopBannerRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))
        val promotionNames = promotionRepository.findAllById(shopBanners.map { it.promotionId }.toSet())
            .associate { it.id to it.name }

        return AdminShopBannersResponse(
            shopBanners = shopBanners.map { createResponse(it, promotionNames.getValue(it.promotionId)) },
        )
    }

    /**
     * 이미지를 새 키에 올린 뒤 배너를 만든다. 올리고 나서 저장이 실패하면 아무도 가리키지 않는 이미지가 남지만 해는 없다.
     * 설명이 비었으면 설명 색도 저장하지 않는다.
     *
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] 비었거나 5MB를 넘을 경우
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] PNG가 아닐 경우
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 종료 시각이 시작 시각보다 앞인 경우
     * @throws BusinessException [AdminErrorCode.PROMOTION_NOT_FOUND] 프로모션이 없는 경우
     * @throws BusinessException [S3ErrorCode.OBJECT_UPLOAD_FAILED] S3 업로드에 실패한 경우(아무것도 만들지 않음)
     */
    fun createShopBanner(
        adminId: Long,
        request: CreateShopBannerRequest,
        image: MultipartFile,
    ): AdminShopBannerResponse {
        val bytes = image.bytes
        adminImageValidator.validateImage(bytes, IMAGE_PURPOSE.maxFileSizeBytes)
        ShopBanner.validatePeriod(request.startsAt, request.endsAt)
        val promotion = getPromotion(request.promotionId)

        val imageKey = uploadImage(bytes)
        val description = convertDescription(request.description)
        val shopBanner = shopBannerRepository.save(
            ShopBanner(
                promotionId = promotion.id,
                imageKey = imageKey,
                title = request.title.trim(),
                description = description,
                titleColor = request.titleColor,
                descriptionColor = request.descriptionColor.takeIf { description != null },
                displayOrder = request.displayOrder,
                startsAt = request.startsAt,
                endsAt = request.endsAt,
                adminId = adminId,
            ),
        )

        return createResponse(shopBanner, promotion.name)
    }

    /**
     * 배너의 글자·순서·기간을 받은 값으로 통째로 바꾼다(PUT). 알리는 프로모션과 이미지는 바꾸지 않는다.
     *
     * @throws BusinessException [AdminErrorCode.SHOP_BANNER_NOT_FOUND] 배너가 없는 경우
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 종료 시각이 시작 시각보다 앞인 경우
     */
    @Transactional
    fun updateShopBanner(adminId: Long, bannerId: Long, request: UpdateShopBannerRequest): AdminShopBannerResponse {
        val shopBanner = getShopBanner(bannerId)

        val description = convertDescription(request.description)
        shopBanner.updatePeriod(request.startsAt, request.endsAt)
        shopBanner.updateTitle(request.title.trim())
        shopBanner.updateTitleColor(request.titleColor)
        shopBanner.updateDescription(description)
        shopBanner.updateDescriptionColor(request.descriptionColor.takeIf { description != null })
        shopBanner.updateDisplayOrder(request.displayOrder)
        shopBanner.updateAdminId(adminId)

        return createResponse(shopBanner, getPromotion(shopBanner.promotionId).name)
    }

    /**
     * 배너 이미지를 새 키에 올리고 배너가 그 키를 가리키게 한다. URL이 바뀌므로 CDN 무효화 없이 다음 조회부터 새 이미지가 나간다.
     *
     * 업로드는 트랜잭션 밖에서 하고, 저장은 [ShopBannerRepository.save] 한 번으로 끝낸다.
     *
     * @throws BusinessException [AdminErrorCode.SHOP_BANNER_NOT_FOUND] 배너가 없는 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] 비었거나 5MB를 넘을 경우
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] PNG가 아닐 경우
     * @throws BusinessException [S3ErrorCode.OBJECT_UPLOAD_FAILED] S3 업로드에 실패한 경우(아무것도 바뀌지 않음)
     */
    fun updateShopBannerImage(adminId: Long, bannerId: Long, image: MultipartFile): AdminShopBannerResponse {
        val shopBanner = getShopBanner(bannerId)

        val bytes = image.bytes
        adminImageValidator.validateImage(bytes, IMAGE_PURPOSE.maxFileSizeBytes)

        shopBanner.updateImageKey(uploadImage(bytes))
        shopBanner.updateAdminId(adminId)
        val saved = shopBannerRepository.save(shopBanner)

        return createResponse(saved, getPromotion(saved.promotionId).name)
    }

    private fun getShopBanner(bannerId: Long): ShopBanner = shopBannerRepository.findByIdOrNull(bannerId)
        ?: throw BusinessException(AdminErrorCode.SHOP_BANNER_NOT_FOUND)

    private fun getPromotion(promotionId: Long): Promotion = promotionRepository.findByIdOrNull(promotionId)
        ?: throw BusinessException(AdminErrorCode.PROMOTION_NOT_FOUND)

    private fun uploadImage(bytes: ByteArray): String {
        val imageKey = IMAGE_PURPOSE.createUniqueObjectKey()
        runBlocking {
            s3StorageClient.uploadObject(
                imageKey,
                bytes,
                AdminImagePurpose.CONTENT_TYPE,
                AdminImagePurpose.CACHE_CONTROL,
            )
        }
        return imageKey
    }

    // 공백만 있는 설명은 없는 것으로 본다
    private fun convertDescription(description: String?): String? = description?.trim()?.ifEmpty { null }

    private fun createResponse(shopBanner: ShopBanner, promotionName: String): AdminShopBannerResponse =
        shopBanner.toAdminShopBannerResponse(
            promotionName = promotionName,
            imageUrl = "${adminProperties.cdnBaseUrl}/${shopBanner.imageKey}",
        )
}
