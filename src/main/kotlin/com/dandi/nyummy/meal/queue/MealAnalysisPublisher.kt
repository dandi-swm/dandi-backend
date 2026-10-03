package com.dandi.nyummy.meal.queue

data class MealAnalysisMessage(val eventId: Long, val mealId: Long)

interface MealAnalysisPublisher {
    fun publish(message: MealAnalysisMessage)
}
