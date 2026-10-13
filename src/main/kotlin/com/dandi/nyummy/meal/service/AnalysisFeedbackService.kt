package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.CreateAnalysisFeedbackRequest
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.mapper.toAnalysisFeedback
import com.dandi.nyummy.meal.repository.AnalysisFeedbackRepository
import com.dandi.nyummy.meal.repository.MealRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * AI 영양 분석 결과에 대한 사용자 평가(별점 + 이유)를 담당한다.
 *
 * 평가는 식사당 1건이고 수정하지 않는다. 분석 성공률 지표의 입력이라 한 식사에 여러 건이 쌓이면
 * 지표가 왜곡된다.
 */
@Service
class AnalysisFeedbackService(
    private val mealRepository: MealRepository,
    private val analysisFeedbackRepository: AnalysisFeedbackRepository,
    private val mealProperties: MealProperties,
) {
    companion object {
        private const val MIN_RATING = 0.5f
        private const val MAX_RATING = 5.0f
        private const val RATING_UNIT = 0.5f

        private const val BUCKET_SIZE = 100

        // mealId는 연속값이라 그대로 나머지를 쓰면 등록 순서와 노출 여부가 맞물린다. 황금비 상수를 곱해 섞는다.
        private const val BUCKET_MIXER = 2654435761L

        private val logger = LoggerFactory.getLogger(AnalysisFeedbackService::class.java)
    }

    /**
     * 식사의 영양 분석 결과에 대한 평가를 등록한다.
     *
     * 분석이 끝난(COMPLETED) 자기 식사만 평가할 수 있다. 분석 중이면 아직 평가할 결과가 없고,
     * 실패한 식사는 평가가 아니라 재시도로 흐른다.
     *
     * 삭제된 식사는 조회 단계에서 걸러진다. 유니크 제약 때문에 평가가 한 번 달리면 되돌릴 수단이
     * 없어서, 사용자가 이미 지운 식사에 평가가 남으면 지표에 그대로 섞인다.
     *
     * @param userId 평가를 등록하는 사용자 ID
     * @param mealId 평가 대상 식사 ID
     * @param request 별점과 이유를 담은 [CreateAnalysisFeedbackRequest]
     * @throws BusinessException [MealErrorCode.INVALID_RATING] 별점이 허용 범위·단위를 벗어난 경우
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] 식사가 없거나 삭제됐거나 요청자 소유가 아닌 경우
     * @throws BusinessException [MealErrorCode.ANALYSIS_NOT_COMPLETED] 영양 분석이 완료되지 않은 경우
     * @throws BusinessException [MealErrorCode.ANALYSIS_FEEDBACK_ALREADY_EXISTS] 이미 평가한 식사인 경우
     */
    @Transactional
    fun createAnalysisFeedback(userId: Long, mealId: Long, request: CreateAnalysisFeedbackRequest) {
        validateRating(request.rating)

        val meal = mealRepository.findMealByIdAndDeletedAtIsNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        if (meal.status != MealStatus.COMPLETED) {
            throw BusinessException(MealErrorCode.ANALYSIS_NOT_COMPLETED)
        }

        if (analysisFeedbackRepository.existsByMealId(mealId)) {
            throw BusinessException(MealErrorCode.ANALYSIS_FEEDBACK_ALREADY_EXISTS)
        }

        val analysisFeedback = request.toAnalysisFeedback(mealId, userId)

        // 위 중복 검사와 저장 사이에 같은 식사의 두 번째 요청이 끼면 유니크 제약
        // (uk_analysis_feedbacks_meal_id)에 걸린다. 별점 버튼은 더블탭이 쉬워 실제로 발생하므로,
        // 500으로 새지 않게 같은 에러코드로 바꿔 내보낸다.
        try {
            analysisFeedbackRepository.save(analysisFeedback)
        } catch (e: DataIntegrityViolationException) {
            logger.warn("피드백 동시 생성으로 유니크 제약과 충돌했습니다: mealId={}", mealId, e)
            throw BusinessException(MealErrorCode.ANALYSIS_FEEDBACK_ALREADY_EXISTS)
        }
    }

    /**
     * 이 식사에 평가를 등록할 수 있는지 판단한다. 앱은 이 값만 보고 별점 UI를 띄운다.
     *
     * 매번 평가를 요청하면 사용자가 피로해지므로 [MealProperties.analysisFeedbackRate] 비율의 식사에만
     * 띄운다. 추첨 결과는 mealId로 결정되어 같은 식사는 몇 번을 조회해도 같다.
     *
     * 추첨에서 빠진 식사라도 평가 요청이 오면 받아준다([createAnalysisFeedback]에는 이 검사가 없다).
     * 추첨은 노출 정책이지 권한이 아니고, 비율을 낮추는 순간 열려 있던 화면의 제출이 거부되면 안 된다.
     *
     * 나머지 거부 조건(분석 완료·중복)은 [createAnalysisFeedback]과 같은 규칙이어야 한다. 등록 쪽은
     * 에러코드를 구분해야 해서 조건별로 따로 검사하므로, 평가 조건이 늘면 두 곳을 함께 고친다.
     *
     * @param meal 평가 가능 여부를 판단할 식사
     * @return 영양 분석이 완료됐고, 추첨 대상이며, 아직 평가하지 않은 식사면 true
     */
    @Transactional(readOnly = true)
    fun canCreateAnalysisFeedback(meal: Meal): Boolean = meal.status == MealStatus.COMPLETED &&
        isFeedbackTarget(meal.id) &&
        !analysisFeedbackRepository.existsByMealId(meal.id)

    /**
     * mealId를 0~99 버킷에 넣고 설정 비율 미만이면 평가 대상으로 본다.
     *
     * 중복 조회보다 앞에서 검사하므로, 추첨에서 빠진 식사는 DB를 읽지 않는다.
     */
    private fun isFeedbackTarget(mealId: Long): Boolean =
        (mealId * BUCKET_MIXER).mod(BUCKET_SIZE) < mealProperties.analysisFeedbackRate

    /**
     * 별점이 0.5~5.0 사이의 0.5 단위 값인지 검증한다. 0.0(별점 없음)은 허용하지 않는다.
     *
     * 0.5 단위 값은 2진 부동소수점으로 정확히 표현되므로 나머지 연산으로 판정해도 오차가 없다.
     * 범위를 벗어난 값, 0.0, 0.5 단위가 아닌 값(3.7 등), NaN·Infinity를 모두 거른다.
     *
     * @throws BusinessException [MealErrorCode.INVALID_RATING] 위 조건을 만족하지 않는 경우
     */
    private fun validateRating(rating: Float) {
        if (rating < MIN_RATING || rating > MAX_RATING || rating % RATING_UNIT != 0.0f) {
            throw BusinessException(MealErrorCode.INVALID_RATING)
        }
    }
}
