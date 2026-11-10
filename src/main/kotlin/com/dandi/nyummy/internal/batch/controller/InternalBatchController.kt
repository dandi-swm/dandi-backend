package com.dandi.nyummy.internal.batch.controller

import com.dandi.nyummy.internal.batch.dto.ReportReconciliationResponse
import com.dandi.nyummy.internal.batch.service.DailyPromotionService
import com.dandi.nyummy.internal.batch.service.MealReminderService
import com.dandi.nyummy.internal.batch.service.ProductSalesCountService
import com.dandi.nyummy.internal.batch.service.ReportReconciliationService
import com.dandi.nyummy.internal.batch.service.RetentionBatchService
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Hidden // Swagger 문서 노출 차단
@RestController
@RequestMapping("api/v1/internal/batch")
class InternalBatchController(
    private val mealReminderService: MealReminderService,
    private val retentionBatchService: RetentionBatchService,
    private val dailyPromotionService: DailyPromotionService,
    private val productSalesCountService: ProductSalesCountService,
    private val reportReconciliationService: ReportReconciliationService,
) {

    // 매시 정각 호출
    @PostMapping("/meals/reminders")
    fun triggerMealReminders(@RequestParam(required = false) hour: Int?) {
        mealReminderService.sendMealReminders(hour)
    }

    // 매일 20:00 호출
    @PostMapping("/retentions/daily-check")
    fun triggerDailyRetentionCheck() {
        retentionBatchService.sendDailyRetentionPushes()
    }

    // 매일 23:50 호출. 다음 날 오늘의 할인을 미리 만든다
    @PostMapping("/shop/daily-promotions")
    fun triggerDailyPromotion(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ) {
        dailyPromotionService.createDailyPromotion(date)
    }

    // 매일 23:40 호출
    @PostMapping("/shop/sales-counts")
    fun triggerSalesCountUpdate() {
        productSalesCountService.updateSalesCounts()
    }

    // 매일 05:00 호출. 불일치를 찾기만 하고, Jenkins 잡이 mismatchCount가 0이 아니면 실패로 끝내 알린다
    @PostMapping("/reports/reconciliation")
    fun triggerReportReconciliation(): ReportReconciliationResponse = reportReconciliationService.reconcileReports()
}
