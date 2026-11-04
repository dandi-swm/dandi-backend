package com.dandi.nyummy.admin.controller

import com.dandi.nyummy.admin.config.AdminPaths
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminItemPurchasesResponse
import com.dandi.nyummy.admin.service.AdminItemPurchaseService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Item Purchase", description = "어드민 상점 구매 내역 API (어드민 서비스 전용)")
@RestController
@RequestMapping("${AdminPaths.PREFIX}/item-purchases")
@SecurityRequirements
@ConditionalOnAdminEnabled
class AdminItemPurchaseController(private val adminItemPurchaseService: AdminItemPurchaseService) {

    @Operation(
        summary = "구매 내역 조회",
        description = "사용자의 구매를 최신순으로 조회한다. 구매가 없거나 없는 사용자면 빈 목록이다. 무한 스크롤용 cursor 방식이다. " +
            "구성품의 status는 NOT_CLAIMED(우편을 받기 전), CLAIMED(받음. 사용자가 지운 아이템 포함), REFUNDED(환불함)다. " +
            "isEquipped는 지금 장착 중인지이고, 장착한 적이 있는지는 환불 미리 보기의 EQUIPPED로 알 수 있다.",
    )
    @GetMapping
    fun getItemPurchases(
        @Parameter(description = "구매한 사용자 ID") @RequestParam userId: Long,
        @Parameter(description = "직전 응답의 nextCursor. 첫 요청에서는 생략한다.") @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "한 번에 조회할 개수 (1~50, 기본 20)")
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(50)
        size: Int,
    ): AdminItemPurchasesResponse = adminItemPurchaseService.getItemPurchases(userId, cursor, size)
}
