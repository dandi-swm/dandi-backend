package com.dandi.nyummy.admin.controller

import com.dandi.nyummy.admin.config.AdminPaths
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminShopBannerResponse
import com.dandi.nyummy.admin.dto.AdminShopBannersResponse
import com.dandi.nyummy.admin.dto.CreateShopBannerRequest
import com.dandi.nyummy.admin.dto.UpdateShopBannerRequest
import com.dandi.nyummy.admin.service.AdminShopBannerService
import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.security.AuthAdmin
import com.dandi.nyummy.security.CurrentAdmin
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@Tag(name = "Admin Shop Banner", description = "어드민 상점 배너 관리 API (어드민 서비스 전용)")
@RestController
@RequestMapping("${AdminPaths.PREFIX}/shop-banners")
@SecurityRequirements
@ConditionalOnAdminEnabled
class AdminShopBannerController(private val adminShopBannerService: AdminShopBannerService) {

    @Operation(
        summary = "배너 목록 조회",
        description = "추천 탭 배너를 최신순으로 조회한다. 페이지를 나누지 않고, 내린 배너도 포함한다. " +
            "imageUrl은 CDN 주소에 저장한 imageKey를 붙인 주소다.",
    )
    @GetMapping
    fun getShopBanners(): AdminShopBannersResponse = adminShopBannerService.getShopBanners()

    @Operation(
        summary = "배너 만들기",
        description = "multipart로 request(JSON)와 image(PNG, 5MB 이하)를 받는다. 이미지를 S3 shop-banners/{임의 이름}.png에 올린 뒤 " +
            "배너를 만든다. 알리는 프로모션(promotionId)은 만든 뒤 바꿀 수 없다. 글자색은 SDUI 색 토큰(contentPrimary 등)이다. " +
            "설명이 비면 설명 색도 저장하지 않는다. 종료 시각이 시작 시각보다 앞이면 api.common.invalidInputValue로 거절한다.",
    )
    @ApiErrorCodes(
        admin = [AdminErrorCode.PROMOTION_NOT_FOUND],
        s3 = [S3ErrorCode.FILE_SIZE_EXCEEDED, S3ErrorCode.UNSUPPORTED_CONTENT_TYPE, S3ErrorCode.OBJECT_UPLOAD_FAILED],
    )
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createShopBanner(
        @CurrentAdmin admin: AuthAdmin,
        @Valid @RequestPart("request") request: CreateShopBannerRequest,
        @Parameter(description = "PNG 이미지 (5MB 이하)") @RequestPart("image") image: MultipartFile,
    ): ResponseEntity<AdminShopBannerResponse> {
        val response = adminShopBannerService.createShopBanner(admin.adminId, request, image)

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response)
    }

    @Operation(
        summary = "배너 수정",
        description = "제목·설명·글자색·노출 순서·기간을 보낸 값으로 통째로 바꾼다. 모두 보내야 하고, description·descriptionColor·endsAt을 " +
            "빼면 null이 된다. 내릴 때는 endsAt에 지금 시각을 보낸다. 알리는 프로모션과 이미지는 바꾸지 않는다.",
    )
    @ApiErrorCodes(admin = [AdminErrorCode.SHOP_BANNER_NOT_FOUND])
    @PutMapping("/{bannerId}")
    fun updateShopBanner(
        @CurrentAdmin admin: AuthAdmin,
        @Parameter(description = "배너 ID") @PathVariable("bannerId") bannerId: Long,
        @Valid @RequestBody request: UpdateShopBannerRequest,
    ): AdminShopBannerResponse = adminShopBannerService.updateShopBanner(admin.adminId, bannerId, request)

    @Operation(
        summary = "배너 이미지 교체",
        description = "새 이미지를 S3 shop-banners/{임의 이름}.png 새 키에 올리고 배너가 그 키를 가리키게 한다. imageUrl이 바뀌므로 " +
            "CDN 무효화 없이 다음 조회부터 새 이미지가 나간다. 이미지는 PNG, 5MB 이하다.",
    )
    @ApiErrorCodes(
        admin = [AdminErrorCode.SHOP_BANNER_NOT_FOUND],
        s3 = [S3ErrorCode.FILE_SIZE_EXCEEDED, S3ErrorCode.UNSUPPORTED_CONTENT_TYPE, S3ErrorCode.OBJECT_UPLOAD_FAILED],
    )
    @PutMapping("/{bannerId}/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun updateShopBannerImage(
        @CurrentAdmin admin: AuthAdmin,
        @Parameter(description = "배너 ID") @PathVariable("bannerId") bannerId: Long,
        @Parameter(description = "PNG 이미지 (5MB 이하)") @RequestPart("image") image: MultipartFile,
    ): AdminShopBannerResponse = adminShopBannerService.updateShopBannerImage(admin.adminId, bannerId, image)
}
