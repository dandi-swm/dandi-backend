package com.dandi.nyummy.internal.batch.controller

import com.dandi.nyummy.internal.batch.service.CatStatusBatchService
import com.dandi.nyummy.internal.batch.service.MealReminderBatchService
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Hidden // Swagger 문서 노출 차단
@RestController
@RequestMapping("api/v1/internal/batch")
class InternalBatchController(
    private val mealReminderBatchService: MealReminderBatchService,
    private val catStatusBatchService: CatStatusBatchService,
) {

    // 매시 정각 호출
    @PostMapping("/meals/reminders")
    fun triggerMealReminders() {
        mealReminderBatchService.sendMealReminders(12)
    }

    // 매일 20:00 호출
    @PostMapping("/retentions/daily-check")
    fun triggerDailyRetentionCheck() {
        catStatusBatchService.sendDailyRetentionPushes()
    }
}
