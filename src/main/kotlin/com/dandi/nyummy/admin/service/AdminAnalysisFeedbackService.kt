package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminAnalysisFeedbacksResponse
import com.dandi.nyummy.admin.mapper.toAdminAnalysisFeedbackMealResponse
import com.dandi.nyummy.admin.mapper.toAdminAnalysisFeedbackResponse
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.meal.repository.AnalysisFeedbackRepository
import com.dandi.nyummy.meal.repository.MealRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@ConditionalOnAdminEnabled
class AdminAnalysisFeedbackService(
    private val analysisFeedbackRepository: AnalysisFeedbackRepository,
    private val mealRepository: MealRepository,
    private val imageService: ImageService,
) {

    /**
     * 분석 평가를 대상 식사와 함께 최신순으로 조회한다. 별점 상한·이유 여부가 null이면 그 조건으로 거르지 않는다.
     *
     * 평가와 식사는 연관관계가 없어 식사를 한 번에 따로 읽는다. 평가의 meal_id는 FK(ON DELETE CASCADE)라
     * 식사가 없는 평가는 없고, 사용자가 지운(soft delete) 식사도 행은 남아 함께 읽힌다.
     */
    @Transactional(readOnly = true)
    fun getAnalysisFeedbacks(
        maxRating: Float?,
        hasReason: Boolean?,
        cursor: Long?,
        size: Int,
    ): AdminAnalysisFeedbacksResponse {
        val analysisFeedbacks = analysisFeedbackRepository.findAnalysisFeedbacksBeforeCursor(
            maxRating = maxRating,
            hasReason = hasReason,
            cursor = cursor ?: Long.MAX_VALUE,
            pageable = PageRequest.of(0, size),
        )
        val mealsById = mealRepository.findAllById(analysisFeedbacks.content.map { it.mealId }).associateBy { it.id }

        return AdminAnalysisFeedbacksResponse(
            analysisFeedbacks = analysisFeedbacks.content.map { analysisFeedback ->
                val meal = mealsById.getValue(analysisFeedback.mealId)
                analysisFeedback.toAdminAnalysisFeedbackResponse(
                    meal.toAdminAnalysisFeedbackMealResponse(imageService.createImageUrl(meal.imageKey)),
                )
            },
            nextCursor = if (analysisFeedbacks.hasNext()) analysisFeedbacks.content.last().id else null,
            hasNext = analysisFeedbacks.hasNext(),
        )
    }
}
