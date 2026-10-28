package com.dandi.nyummy.admin.controller

import com.dandi.nyummy.admin.config.AdminPaths
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminProductResponse
import com.dandi.nyummy.admin.dto.AdminProductsResponse
import com.dandi.nyummy.admin.dto.CreateProductRequest
import com.dandi.nyummy.admin.dto.UpdateProductRequest
import com.dandi.nyummy.admin.service.AdminProductService
import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.room.enum.RoomItemCategory
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Product", description = "어드민 상점 상품 관리 API (어드민 서비스 전용)")
@RestController
@RequestMapping("${AdminPaths.PREFIX}/products")
@SecurityRequirements
@ConditionalOnAdminEnabled
class AdminProductController(private val adminProductService: AdminProductService) {

    @Operation(
        summary = "상품 목록 조회",
        description = "상품을 최신순으로 조회한다. 페이지를 나누지 않고, 판매가 끝난 상품도 포함한다. " +
            "판매 중·예약·종료는 saleStartsAt·saleEndsAt으로 화면이 가른다.",
    )
    @GetMapping
    fun getProducts(
        @Parameter(description = "분류. 생략하면 모든 분류") @RequestParam(required = false) category: RoomItemCategory?,
    ): AdminProductsResponse = adminProductService.getProducts(category)

    @Operation(
        summary = "상품 만들기",
        description = "마이룸 아이템으로 상품을 만든다. 구성품이 하나면 단품, 여럿이면 세트다. " +
            "단품은 분류가 아이템과 같고 구성품 정가가 상품 정가와 같아야 한다. 세트는 방 배경을 넣을 수 없고, " +
            "정가가 구성품 정가 합 이하여야 한다. 대표 구성품은 구성품 중 하나다. 이름은 앞뒤 공백을 다듬어 저장한다. " +
            "규칙에 어긋나면 api.common.invalidInputValue와 이유를 담은 message로 거절한다. " +
            "정가·분류·구성품은 만든 뒤 바꿀 수 없다.",
    )
    @ApiErrorCodes(admin = [AdminErrorCode.ROOM_ITEM_NOT_FOUND])
    @PostMapping
    fun createProduct(@Valid @RequestBody request: CreateProductRequest): ResponseEntity<AdminProductResponse> {
        val response = adminProductService.createProduct(request)

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response)
    }

    @Operation(
        summary = "상품 수정",
        description = "이름·기간 한정·판매 기간·1인 구매 한도를 보낸 값으로 통째로 바꾼다. 모두 보내야 하고, " +
            "saleEndsAt·purchaseLimit을 빼면 null(종료일 없음·무제한)이 된다. 판매 중지는 saleEndsAt에 지금 시각을 보낸다. " +
            "정가·분류·구성품·대표 구성품은 바꾸지 않는다.",
    )
    @ApiErrorCodes(admin = [AdminErrorCode.PRODUCT_NOT_FOUND])
    @PutMapping("/{productId}")
    fun updateProduct(
        @Parameter(description = "상품 ID") @PathVariable("productId") productId: Long,
        @Valid @RequestBody request: UpdateProductRequest,
    ): AdminProductResponse = adminProductService.updateProduct(productId, request)
}
