package com.dandi.nyummy.admin.controller

import com.dandi.nyummy.admin.config.AdminPaths
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.CreateItemPurchaseRefundRequest
import com.dandi.nyummy.admin.dto.ItemPurchaseRefundPreviewResponse
import com.dandi.nyummy.admin.dto.ItemPurchaseRefundResponse
import com.dandi.nyummy.admin.service.AdminItemPurchaseRefundService
import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.security.AuthAdmin
import com.dandi.nyummy.security.CurrentAdmin
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Item Purchase Refund", description = "어드민 상점 구매 환불 API (어드민 서비스 전용)")
@RestController
@RequestMapping("${AdminPaths.PREFIX}/item-purchase-products")
@SecurityRequirements
@ConditionalOnAdminEnabled
class AdminItemPurchaseRefundController(private val adminItemPurchaseRefundService: AdminItemPurchaseRefundService) {

    @Operation(
        summary = "환불 미리 보기",
        description = "구성품을 환불하면 돌려줄 코인과 정책에 어긋나는 이유를 계산한다. 아무것도 바꾸지 않는다. " +
            "policyExceptions는 환불 전체의 이유이고, lines[].policyExceptions는 구성품별 이유다. " +
            "ZERO_COIN은 환불 한 건에 하나라 전체에만 있다. 실행할 때는 coinQuantity를 expectedCoinQuantity로 보낸다.",
    )
    @ApiErrorCodes(
        admin = [
            AdminErrorCode.ITEM_PURCHASE_PRODUCT_NOT_FOUND,
            AdminErrorCode.ITEM_PURCHASE_LINE_NOT_FOUND,
            AdminErrorCode.ALREADY_REFUNDED,
        ],
    )
    @GetMapping("/{itemPurchaseProductId}/refund-preview")
    fun getRefundPreview(
        @Parameter(description = "구매 상품 ID") @PathVariable("itemPurchaseProductId") itemPurchaseProductId: Long,
        @Parameter(description = "환불할 구성품 ID. 모두 이 구매 상품의 구성품이어야 한다")
        @RequestParam
        @NotEmpty
        itemPurchaseLineIds: List<Long>,
    ): ItemPurchaseRefundPreviewResponse =
        adminItemPurchaseRefundService.getRefundPreview(itemPurchaseProductId, itemPurchaseLineIds)

    @Operation(
        summary = "환불 실행",
        description = "구성품을 회수하고 코인을 돌려준다. 받은 구성품은 보유 아이템에서 빠지고(장착 해제), 받기 전 구성품은 우편에서 빠진다. " +
            "다시 계산한 코인이 expectedCoinQuantity와 다르면 409로 거부한다. 그사이 같은 세트의 다른 구성품을 환불한 경우다. " +
            "미리 보기와 같은 정책 예외가 있으면 isPolicyException이 true일 때만 환불하고, 아니면 409로 거부한다. " +
            "돌려줄 코인이 0이면 코인 내역을 남기지 않는다.",
    )
    @ApiErrorCodes(
        admin = [
            AdminErrorCode.ITEM_PURCHASE_PRODUCT_NOT_FOUND,
            AdminErrorCode.ITEM_PURCHASE_LINE_NOT_FOUND,
            AdminErrorCode.ALREADY_REFUNDED,
            AdminErrorCode.REFUND_COIN_QUANTITY_CHANGED,
            AdminErrorCode.REFUND_POLICY_EXCEPTION_REQUIRED,
        ],
        common = [CommonErrorCode.REQUEST_CONFLICT],
    )
    @PostMapping("/{itemPurchaseProductId}/refunds")
    fun createRefund(
        @CurrentAdmin admin: AuthAdmin,
        @Parameter(description = "구매 상품 ID") @PathVariable("itemPurchaseProductId") itemPurchaseProductId: Long,
        @Valid @RequestBody request: CreateItemPurchaseRefundRequest,
    ): ResponseEntity<ItemPurchaseRefundResponse> {
        val response = adminItemPurchaseRefundService.createRefund(admin.adminId, itemPurchaseProductId, request)

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response)
    }
}
