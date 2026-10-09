package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisResult
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealAnalysisQueue
import com.dandi.nyummy.meal.enum.MealAnalysisQueueStatus
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.mapper.toMealStatusResponse
import com.dandi.nyummy.meal.queue.ClaimedMealAnalysis
import com.dandi.nyummy.meal.repository.MealAnalysisQueueRepository
import com.dandi.nyummy.meal.repository.MealRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class AnalysisService(
    private val mealRepository: MealRepository,
    private val queueRepository: MealAnalysisQueueRepository,
    private val mealService: MealService,
    private val clock: Clock,
) {
    /**
     * 식사의 영양 분석 상태를 조회한다.
     *
     * @param userId 조회를 요청한 사용자 ID (소유권 검증에 사용)
     * @param mealId 조회할 [Meal]의 ID
     * @return 분석 상태를 담은 [MealStatusResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, userId가 소유자가 아닌 경우
     */
    @Transactional(readOnly = true)
    fun getNutritionAnalysisStatus(userId: Long, mealId: Long): MealStatusResponse {
        val meal = mealRepository.getMealByIdAndDeletedAtIsNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND, "Meal Not Found")

        meal.validateOwnership(userId)

        return meal.toMealStatusResponse()
    }

    /**
     * FAILED 상태의 식사에 새 비동기 영양 분석을 요청한다.
     *
     * @param userId 재시도를 요청한 사용자 ID (소유권 검증에 사용)
     * @param mealId 재시도할 [Meal]의 ID
     * @return 재시도 접수 상태(WAITING)를 담은 [MealStatusResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, userId가 소유자가 아닌 경우
     * @throws BusinessException [MealErrorCode.ANALYSIS_NOT_RETRYABLE] 식사가 FAILED 상태가 아닌 경우
     */
    @Transactional
    fun retryNutritionAnalysis(userId: Long, mealId: Long): MealStatusResponse {
        val meal = mealRepository.findByIdForUpdate(mealId)
            ?.takeIf { it.deletedAt == null }
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)
        meal.validateOwnership(userId)
        if (meal.status != MealStatus.FAILED) {
            throw BusinessException(MealErrorCode.ANALYSIS_NOT_RETRYABLE)
        }

        meal.updateStatus(MealStatus.WAITING)
        mealService.createMealOutbox(meal.id)
        return meal.toMealStatusResponse()
    }

    /**
     * READY 상태의 분석 작업을 최대 [limit]개 선점해 분석을 시작한다.
     *
     * 선점한 작업은 PROCESSING으로, 식사는 ANALYZING으로 바꾼다.
     * 식사가 삭제되었거나 WAITING이 아니면 작업을 FAILED로 끝내고 결과에서 제외한다.
     *
     * READ_COMMITTED에서 실행한다. MySQL REPEATABLE_READ의 갭 락은
     * 서로 다른 작업의 상태 변경도 대기시킬 수 있다.
     *
     * @param limit 선점할 최대 작업 수 (양수)
     * @return 분석을 시작한 작업 목록. Worker가 트랜잭션 밖에서 AI 호출에 사용한다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun startNutritionAnalyses(limit: Int): List<ClaimedMealAnalysis> {
        require(limit > 0)
        val now = Instant.now(clock)
        return queueRepository.findReadyForUpdate(limit).mapNotNull { job ->
            job.start(now)
            val meal = mealRepository.findByIdForUpdate(job.mealId)
            if (meal == null || meal.deletedAt != null || meal.status != MealStatus.WAITING) {
                job.fail(now)
                return@mapNotNull null
            }
            meal.updateStatus(MealStatus.ANALYZING)
            ClaimedMealAnalysis(job.id, meal.id, meal.imageKey, meal.name)
        }
    }

    /**
     * 분석 결과를 식사에 저장하고 식사를 COMPLETED, 작업을 DONE으로 끝낸다.
     *
     * 작업이 이미 끝났으면 아무것도 하지 않는다. 분석 중 식사가 삭제되었거나
     * ANALYZING이 아니게 되었으면 결과를 버리고 작업만 FAILED로 끝낸다.
     * 분석 중 사용자가 변경한 이름은 보존한다.
     *
     * @param job [startNutritionAnalyses]로 시작한 작업
     * @param result AI 영양 분석 결과
     */
    @Transactional
    fun completeNutritionAnalysis(job: ClaimedMealAnalysis, result: NutritionAnalysisResult) {
        val queued = queueRepository.findByIdForUpdate(job.queueId) ?: return
        if (queued.status != MealAnalysisQueueStatus.PROCESSING) return
        check(queued.mealId == job.mealId)

        val now = Instant.now(clock)
        val meal = mealRepository.findByIdForUpdate(queued.mealId)
        if (meal == null || meal.deletedAt != null || meal.status != MealStatus.ANALYZING) {
            queued.fail(now)
            return
        }

        val editedName = meal.name.takeIf { it != job.mealName }
        meal.updateAnalysisResult(result)
        if (editedName != null) meal.updateName(editedName)
        meal.updateStatus(MealStatus.COMPLETED)
        queued.complete(now)
    }

    /**
     * 진행 중인 분석을 실패로 끝낸다. 식사가 ANALYZING이면 FAILED로 바꾸고, 작업은 FAILED로 끝낸다.
     *
     * 작업이 없거나 이미 끝났으면 아무것도 하지 않는다.
     *
     * @param queueId 실패 처리할 [MealAnalysisQueue]의 ID
     */
    @Transactional
    fun failNutritionAnalysis(queueId: Long) {
        val job = queueRepository.findByIdForUpdate(queueId) ?: return
        if (job.status != MealAnalysisQueueStatus.PROCESSING) return

        val meal = mealRepository.findByIdForUpdate(job.mealId)
        if (meal != null && meal.deletedAt == null && meal.status == MealStatus.ANALYZING) {
            meal.updateStatus(MealStatus.FAILED)
        }
        job.fail(Instant.now(clock))
    }
}
