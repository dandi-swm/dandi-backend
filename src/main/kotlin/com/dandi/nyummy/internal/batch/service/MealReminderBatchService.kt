package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.user.repository.ProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class MealReminderBatchService(
    private val profileRepository: ProfileRepository,
    private val mealRepository: MealRepository,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(MealReminderBatchService::class.java)

    fun sendMealReminders(hour: Int) {
        val now = Instant.now(clock)
        val oneHourAgo = now.minus(1, ChronoUnit.HOURS)

        val targets = profileRepository.findNotificationTargetsByHour(hour)

        val list = ArrayList<Long>()
        for (userProfile in targets) {
            if (mealRepository.existsCompletedMealAfter(
                    userId = userProfile.userId,
                    status = MealStatus.COMPLETED,
                    mealAt = oneHourAgo,
                )
            ) {
                continue
            }

            list.add(userProfile.userId)

            // TODO: FCM 발송
        }

        logger.info("meal reminder target list: $list")
    }
}
