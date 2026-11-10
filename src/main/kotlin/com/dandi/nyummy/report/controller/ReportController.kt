package com.dandi.nyummy.report.controller

import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ReportErrorCode
import com.dandi.nyummy.header.CommonHeader
import com.dandi.nyummy.header.CurrentCommonHeader
import com.dandi.nyummy.report.dto.CreateReportPurchaseRequest
import com.dandi.nyummy.report.dto.ReportPurchaseResponse
import com.dandi.nyummy.report.dto.ReportResponse
import com.dandi.nyummy.report.dto.ReportsResponse
import com.dandi.nyummy.report.service.ReportPurchaseService
import com.dandi.nyummy.report.service.ReportService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Report", description = "AI 주간 영양 리포트 API")
@RestController
@RequestMapping("/api/v1/reports")
class ReportController(
    private val reportService: ReportService,
    private val reportPurchaseService: ReportPurchaseService,
) {
    companion object {
        private const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }

    @Operation(
        summary = "리포트 목록 조회",
        description = "리포트를 최신순으로 조회한다. 무한 스크롤용 cursor 방식이다. " +
            "첫 요청은 cursor 없이 보내고, 다음 요청부터는 직전 응답의 nextCursor를 cursor로 보낸다. " +
            "hasNext가 false면 마지막 묶음이며 nextCursor는 null이다. " +
            "purchasePrice는 지금 구매하면 낼 코인이고(첫 리포트는 0, 그 뒤 100), " +
            "minMealCount는 그 주에 분석이 완료된 식사가 이만큼 있어야 살 수 있다는 뜻이다. 두 값은 모든 묶음에서 같다. " +
            "title은 COMPLETED일 때만 있다. isUnread는 완료됐지만 아직 상세를 열어 보지 않은 리포트다. " +
            "isRefunded는 취소·생성 실패로 코인을 돌려준 리포트이고, 무료로 산 리포트는 취소·실패해도 false다.",
    )
    @GetMapping
    fun getReports(
        @CurrentUser user: AuthUser,
        @Parameter(description = "직전 응답의 nextCursor. 첫 요청에서는 생략한다.") @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "한 번에 조회할 개수 (1~50, 기본 10)")
        @RequestParam(defaultValue = "10")
        @Min(1)
        @Max(50)
        size: Int,
    ): ReportsResponse = reportService.getReports(user.userId, cursor, size)

    @Operation(
        summary = "리포트 구매",
        description = "코인을 내고 한 주(일요일~토요일)의 리포트를 산다. 리포트는 WAITING으로 생기고 비동기로 만들어진다. " +
            "startDate는 받을 주의 일요일(Asia/Seoul)이고, 이미 끝난 주만 살 수 있다. " +
            "purchasePrice는 목록에서 본 가격이다. 차감할 코인은 서버가 정하고, 화면 가격과 다르면 409 priceChanged다. " +
            "그 주에 분석이 완료된 식사가 minMealCount보다 적으면 400 notEnoughMeals다(지운 식사, 분석 중·실패한 식사는 세지 않는다). " +
            "취소·실패하지 않은 같은 주의 리포트가 있으면 409 duplicateWeek다. 취소·실패한 주는 다시 살 수 있다. " +
            "앱은 받을 주를 고를 때 Idempotency-Key(UUID)를 만들고, 응답을 못 받아 다시 보낼 때만 같은 키를 보낸다. " +
            "같은 키로 다시 오면 처음 결과를 그대로 돌려주고, 같은 키로 다른 주·가격을 보내면 409 idempotencyKeyReused다. " +
            "앞선 요청이 아직 처리 중이면 409 purchaseInProgress이고, 결과를 알 수 없으므로 같은 키로 다시 보낸다.",
    )
    @ApiErrorCodes(
        report = [
            ReportErrorCode.INVALID_WEEK,
            ReportErrorCode.NOT_ENOUGH_MEALS,
            ReportErrorCode.DUPLICATE_WEEK,
            ReportErrorCode.PRICE_CHANGED,
            ReportErrorCode.INSUFFICIENT_BALANCE,
            ReportErrorCode.IDEMPOTENCY_KEY_REUSED,
            ReportErrorCode.PURCHASE_IN_PROGRESS,
        ],
    )
    @PostMapping
    fun createReportPurchase(
        @CurrentUser user: AuthUser,
        @CurrentCommonHeader commonHeader: CommonHeader,
        @Parameter(description = "받을 주를 골랐을 때 앱이 만드는 UUID. 재시도할 때만 같은 값을 보낸다 (1~64자)")
        @RequestHeader(IDEMPOTENCY_KEY)
        @Size(min = 1, max = 64)
        idempotencyKey: String,
        @Valid @RequestBody request: CreateReportPurchaseRequest,
    ): ReportPurchaseResponse =
        reportPurchaseService.createReportPurchase(user.userId, idempotencyKey, request, commonHeader.appVersion)

    @Operation(
        summary = "리포트 상세 조회",
        description = "본인 리포트 하나를 조회한다. title·viewType·content·completedAt은 COMPLETED일 때만 있다. " +
            "앱은 viewType의 네이티브 뷰로 content를 그린다. content는 생성 시점 스냅샷이라 나중에 식사를 고쳐도 바뀌지 않는다. " +
            "완료된 리포트를 처음 조회하면 읽음으로 기록해, 그 뒤 목록의 isUnread는 false다.",
    )
    @ApiErrorCodes(report = [ReportErrorCode.REPORT_NOT_FOUND], auth = [AuthErrorCode.FORBIDDEN])
    @GetMapping("/{reportId}")
    fun getReport(
        @CurrentUser user: AuthUser,
        @Parameter(description = "리포트 ID") @PathVariable("reportId") reportId: Long,
    ): ReportResponse = reportService.getReport(user.userId, reportId)
}
