package com.dandi.nyummy.admin.controller

import com.dandi.nyummy.admin.config.AdminPaths
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminPromotionResponse
import com.dandi.nyummy.admin.dto.AdminPromotionsResponse
import com.dandi.nyummy.admin.dto.CreatePromotionRequest
import com.dandi.nyummy.admin.dto.UpdatePromotionProductsRequest
import com.dandi.nyummy.admin.dto.UpdatePromotionRequest
import com.dandi.nyummy.admin.service.AdminPromotionService
import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Promotion", description = "어드민 상점 프로모션 관리 API (어드민 서비스 전용)")
@RestController
@RequestMapping("${AdminPaths.PREFIX}/promotions")
@SecurityRequirements
@ConditionalOnAdminEnabled
class AdminPromotionController(private val adminPromotionService: AdminPromotionService) {

    @Operation(
        summary = "프로모션 목록 조회",
        description = "프로모션을 최신순으로 조회한다. 페이지를 나누지 않고, 끝난 프로모션도 포함한다. " +
            "진열 상품을 진열 순서대로 붙이고, 상품마다 이 프로모션의 할인율만 적용한 결제가(purchasePrice)를 함께 준다.",
    )
    @GetMapping
    fun getPromotions(): AdminPromotionsResponse = adminPromotionService.getPromotions()

    @Operation(
        summary = "프로모션 만들기",
        description = "프로모션을 만들고 productIds를 그 순서대로 진열한다. discountRate를 비우면 할인 없이 상품만 묶는다. " +
            "사용자에게는 진행 중이고 대상인 프로모션 중 할인율이 가장 큰 것 하나만 적용된다. 대상(category)은 만든 뒤 바꿀 수 없다. " +
            "같은 상품을 두 번 넣거나 종료 시각이 시작 시각보다 앞이면 api.common.invalidInputValue로 거절한다.",
    )
    @ApiErrorCodes(admin = [AdminErrorCode.PRODUCT_NOT_FOUND])
    @PostMapping
    fun createPromotion(@Valid @RequestBody request: CreatePromotionRequest): ResponseEntity<AdminPromotionResponse> {
        val response = adminPromotionService.createPromotion(request)

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response)
    }

    @Operation(
        summary = "프로모션 수정",
        description = "이름·할인율·기간을 보낸 값으로 통째로 바꾼다. 모두 보내야 하고, discountRate·endsAt을 빼면 " +
            "null(할인 없음·종료일 없음)이 된다. 끝낼 때는 endsAt에 지금 시각을 보낸다. 할인율을 바꾸면 장바구니에 담긴 상품의 " +
            "결제가도 바로 바뀐다(구매 시 api.shop.priceChanged). 대상과 진열 상품은 바꾸지 않는다.",
    )
    @ApiErrorCodes(admin = [AdminErrorCode.PROMOTION_NOT_FOUND])
    @PutMapping("/{promotionId}")
    fun updatePromotion(
        @Parameter(description = "프로모션 ID") @PathVariable("promotionId") promotionId: Long,
        @Valid @RequestBody request: UpdatePromotionRequest,
    ): AdminPromotionResponse = adminPromotionService.updatePromotion(promotionId, request)

    @Operation(
        summary = "프로모션 진열 상품 교체",
        description = "진열 상품을 보낸 목록으로 통째로 바꾼다. 목록 순서가 진열 순서다. 비우면 진열 상품이 없어진다. " +
            "같은 상품을 두 번 넣으면 api.common.invalidInputValue로 거절한다.",
    )
    @ApiErrorCodes(admin = [AdminErrorCode.PROMOTION_NOT_FOUND, AdminErrorCode.PRODUCT_NOT_FOUND])
    @PutMapping("/{promotionId}/products")
    fun updatePromotionProducts(
        @Parameter(description = "프로모션 ID") @PathVariable("promotionId") promotionId: Long,
        @Valid @RequestBody request: UpdatePromotionProductsRequest,
    ): AdminPromotionResponse = adminPromotionService.updatePromotionProducts(promotionId, request)
}
