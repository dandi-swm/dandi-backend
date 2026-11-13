package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.calculator.calculateMealNutrition
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.NutritionAnalysisResult
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.Icon
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealAnalysisJob
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealRecognitionCategory
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.meal.service.MealFoodService
import com.dandi.nyummy.worker.config.ConditionalOnAnalysisWorkerEnabled
import com.dandi.nyummy.worker.config.WorkerJobProperties
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.AnalysisMealFoodResult
import com.dandi.nyummy.worker.dto.AnalysisMealResult
import com.dandi.nyummy.worker.dto.AnalysisPushResult
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Worker가 쓰는 짧은 트랜잭션을 모은다. Gemini 호출처럼 오래 걸리는 일은 이 트랜잭션들 밖에서 한다.
 *
 * 잠그는 순서는 작업(meal_analysis_jobs) → 식사(meals)로 고정한다. 반대 순서로 잠그는 트랜잭션이 있으면 서로를 기다려 데드락이 난다.
 *
 * 결과를 쓰는 메서드는 모두 작업이 아직 그 번호(leaseCount)일 때만 쓴다(fencing). lease가 만료돼 다른 Worker가 같은 작업을 가져갔으면
 * 늦게 끝난 Worker는 아무것도 쓰지 않고 끝난다.
 */
@Service
@ConditionalOnAnalysisWorkerEnabled
class MealAnalysisJobTransactionService(
    private val mealAnalysisJobRepository: MealAnalysisJobRepository,
    private val mealRepository: MealRepository,
    private val mealFoodService: MealFoodService,
    private val catRepository: CatRepository,
    private val catService: CatService,
    private val workerProperties: WorkerProperties,
    private val clock: Clock,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(MealAnalysisJobTransactionService::class.java)
    }

    /**
     * 대기 중이거나 lease가 만료된 작업을 [limit]개까지 lease로 가져가고, 그 식사들을 ANALYZING으로 바꾼다.
     * 인식 작업의 식사만은 RECOGNIZING 그대로 둔다. ANALYZING은 음식 목록이 있고 그 영양을 계산하는 중이라는 뜻이라,
     * 아직 음식 목록이 없는 식사에는 쓰지 않는다.
     *
     * 작업 행을 잠근 채(SKIP LOCKED) lease와 lease_count를 올리므로, 같은 작업을 두 Worker가 함께 가져가지 않는다.
     * 돌려주는 leaseCount는 읽은 값 + 1로, 커밋된 lease_count와 같다.
     *
     * 가져가지 않고 끝내는 작업도 있다.
     * - 그사이 사용자가 지운 식사의 작업은 지운다. 분석하지 않으므로 Gemini 비용과 알림이 생기지 않는다.
     * - 이미 [WorkerJobProperties.maxLeaseCount]번 가져갔는데 또 lease가 만료된 작업은 지우고 식사를 FAILED로 둔다.
     *   처리하던 Worker가 거듭 죽은 경우라, 다시 가져가도 같은 일이 반복될 수 있다. 알림은 보내지 않는다.
     *
     * @param limit 이번에 가져갈 최대 개수 (Worker의 빈 자리 수)
     * @return 가져간 작업. 가져갈 작업이 없으면 빈 목록
     */
    @Transactional
    fun leaseMealAnalysisJobs(limit: Int): List<MealAnalysisJobResult> {
        val jobs = mealAnalysisJobRepository.findMealAnalysisJobsWithLock(limit)
        if (jobs.isEmpty()) {
            return emptyList()
        }

        val mealsById = mealRepository.findAllById(jobs.map { it.mealId }).associateBy { it.id }
        val (liveJobs, deletedJobs) = jobs.partition { job ->
            val meal = mealsById[job.mealId]
            meal != null && meal.deletedAt == null
        }
        val (leasableJobs, exhaustedJobs) = liveJobs.partition {
            it.leaseCount < workerProperties.analysis.maxLeaseCount
        }

        if (deletedJobs.isNotEmpty()) {
            logger.info("지워진 식사의 분석 작업을 건너뜁니다: mealIds={}", deletedJobs.map { it.mealId })
            mealAnalysisJobRepository.deleteAllByIdInBatch(deletedJobs.map { it.id })
        }
        if (exhaustedJobs.isNotEmpty()) {
            logger.warn("최대 시도 횟수를 채운 분석 작업을 FAILED로 끝냅니다: mealIds={}", exhaustedJobs.map { it.mealId })
            mealAnalysisJobRepository.deleteAllByIdInBatch(exhaustedJobs.map { it.id })
            mealRepository.updateMealStatuses(exhaustedJobs.map { it.mealId }, MealStatus.FAILED, Instant.now(clock))
        }
        if (leasableJobs.isEmpty()) {
            return emptyList()
        }

        mealAnalysisJobRepository.updateLease(
            leasableJobs.map {
                it.id
            },
            workerProperties.analysis.leaseTimeToLive.toSeconds(),
        )
        val analyzingMealIds = leasableJobs
            .filter { it.category != MealAnalysisJobCategory.RECOGNITION }
            .map { it.mealId }
        if (analyzingMealIds.isNotEmpty()) {
            mealRepository.updateMealStatuses(analyzingMealIds, MealStatus.ANALYZING, Instant.now(clock))
        }

        return leasableJobs.map {
            val leaseCount = it.leaseCount + 1
            MealAnalysisJobResult(
                jobId = it.id,
                mealId = it.mealId,
                leaseCount = leaseCount,
                imageKey = mealsById.getValue(it.mealId).imageKey,
                canRetry = leaseCount < workerProperties.analysis.maxLeaseCount,
                createdAt = it.createdAt,
                language = it.language ?: Language.ORIGINAL_LANGUAGE,
                category = it.category,
            )
        }
    }

    /**
     * 분석 결과를 저장하고 COMPLETED로 확정한다. 작업은 지운다.
     *
     * 고양이의 마지막 식사 시각도 같이 갱신한다. 리텐션 배치가 이 값으로 대상을 고른다. 가입 때 만들어지는 고양이가 없으면
     * 갱신만 건너뛴다. [CatService.updateLastMealAt]이 던지는 예외는 이 트랜잭션을 롤백 전용으로 만들어 결과까지 잃게 하므로
     * 먼저 있는지 확인한다.
     *
     * @return 알림에 쓸 값. 이 Worker의 작업이 아니면(lease가 만료돼 다른 Worker가 가져갔으면) null
     */
    @Transactional
    fun confirmMealAnalysis(job: MealAnalysisJobResult, analysisResult: NutritionAnalysisResult): AnalysisPushResult? {
        val meal = deleteJobAndGetMeal(job) ?: return null

        meal.updateAnalysisResult(analysisResult)
        meal.updateStatus(MealStatus.COMPLETED)

        if (catRepository.existsByUserId(meal.userId)) {
            catService.updateLastMealAt(meal.userId, meal.mealAt)
        } else {
            logger.error("가입 시 생성되어야 할 고양이가 없어 마지막 식사 시각을 갱신하지 않습니다: userId={}", meal.userId)
        }

        return convertAnalysisPushResult(meal)
    }

    /**
     * 인식 결과를 저장하고 식사를 WAITING으로 바꾼 뒤, 인식 작업을 지우고 [nutritionDelay] 뒤부터 가져갈 영양 작업을 만든다.
     * 그 시간 안에 사용자가 음식 목록을 보내면 그 작업을 지우고 바로 계산한다.
     *
     * 인식 작업 행을 영양 작업으로 바꿔 쓰지 않고 새 행을 만든다. 같은 행을 쓰면 영양 작업의 시도 횟수를 0부터 다시 세야 하는데,
     * 그러면 늦게 끝난 인식 Worker의 번호가 영양 작업의 번호와 같아져 결과를 쓸 수 있다. 새 행은 id가 달라 그럴 일이 없다.
     *
     * 인식하는 동안 사용자가 식사를 지웠으면 인식 작업만 지우고 끝낸다. 음식 줄과 영양 작업을 만들지 않아 더 계산하지 않는다.
     *
     * @param recognition 음식 인식 결과
     * @param nutritionDelay 영양 작업을 가져가기까지 기다리는 시간
     * @return 이 Worker의 작업이라 인식 작업을 끝냈으면 true. lease가 만료돼 다른 Worker가 가져갔으면 false
     */
    @Transactional
    fun confirmMealRecognition(
        job: MealAnalysisJobResult,
        recognition: MealRecognitionResult,
        nutritionDelay: Duration,
    ): Boolean {
        val meal = deleteJobAndGetMeal(job) ?: return false
        if (meal.deletedAt != null) {
            logger.info("인식하는 동안 지워진 식사라 음식 줄과 영양 작업을 만들지 않습니다: mealId={}", meal.id)
            return true
        }

        meal.updateName(recognition.name)
        meal.updateInputCategory(recognition.inputCategory)
        meal.updateStatus(MealStatus.WAITING)
        mealFoodService.createMealFoods(meal.id, recognition, MealRecognitionCategory.WORKER)

        val nutritionJob = mealAnalysisJobRepository.save(
            MealAnalysisJob(
                mealId = meal.id,
                language = job.language,
                category = MealAnalysisJobCategory.NUTRITION,
            ),
        )
        if (nutritionDelay.toSeconds() > 0) {
            mealAnalysisJobRepository.updateLeaseExpiresAt(nutritionJob.id, nutritionDelay.toSeconds())
        }

        return true
    }

    /**
     * 영양 작업이 영양을 계산할 식사와 그 음식 줄을 읽는다. 1인분 조회와 코멘트는 이 값을 들고 트랜잭션 밖에서 한다.
     *
     * 여기서 읽은 뒤 사용자가 음식 목록을 다시 보내면 작업 행이 새로 만들어지므로, 이 값으로 계산한 결과는
     * [confirmMealNutrition]이 쓰지 않고 버린다.
     *
     * @return 식사 행이 없으면 null
     */
    @Transactional(readOnly = true)
    fun getAnalysisMeal(job: MealAnalysisJobResult): AnalysisMealResult? {
        val meal = mealRepository.findById(job.mealId).orElse(null) ?: return null

        return AnalysisMealResult(
            userId = meal.userId,
            mealAt = meal.mealAt,
            // 음식 줄이 있는 식사는 인식을 거쳤으므로 유형이 있다. 없으면 가장 흔한 음식 사진으로 본다.
            inputCategory = meal.inputCategory ?: MealInputCategory.FOOD_PHOTO,
            hasCatComment = meal.catComment != null,
            mealFoods = mealFoodService.getMealFoods(meal.id).map {
                AnalysisMealFoodResult(
                    mealFoodId = it.id,
                    name = it.name,
                    portion = it.portion,
                    servingNutrition = it.servingNutrition,
                    iconId = it.iconId,
                )
            },
        )
    }

    /**
     * 1인분 조회 결과를 음식 줄에 채우고, 식사의 합계와 아이콘을 저장해 COMPLETED로 확정한다. 작업은 지운다.
     *
     * 식사 합계는 모든 줄의 1인분 × 인분을 더한 값이고, 식사 아이콘은 맨 위 줄(대표 음식)의 아이콘이다.
     * 코멘트는 식사에 아직 없을 때만 저장한다. 음식 목록을 고쳐 다시 계산해도 처음의 코멘트를 그대로 둔다.
     * 고양이의 마지막 식사 시각을 갱신하는 방식은 [confirmMealAnalysis]와 같다.
     *
     * @param servingNutritions 음식 줄 ID별 1인분 조회 결과. 조회하지 않은 줄은 없다
     * @param catComment 새로 만든 코멘트. 이미 코멘트가 있어 만들지 않았으면 null
     * @return 알림에 쓸 값. 이 Worker의 작업이 아니면(lease가 만료됐거나 음식 목록을 다시 보내 작업이 바뀌었으면) null
     * @throws IllegalStateException 음식 줄이 없거나 1인분 값이 없는 줄이 남은 경우. 트랜잭션이 롤백돼 작업이 남는다
     */
    @Transactional
    fun confirmMealNutrition(
        job: MealAnalysisJobResult,
        servingNutritions: Map<Long, ServingNutritionResult>,
        catComment: String?,
    ): AnalysisPushResult? {
        val meal = deleteJobAndGetMeal(job) ?: return null

        val mealFoods = mealFoodService.updateServingNutritions(meal.id, servingNutritions)
        check(mealFoods.isNotEmpty()) { "영양을 계산할 음식 줄이 없습니다: mealId=${meal.id}" }

        meal.updateNutrition(calculateMealNutrition(mealFoods))
        meal.updateIconId(mealFoods.first().iconId ?: Icon.DEFAULT_ID)
        if (meal.catComment == null && catComment != null) {
            meal.updateCatComment(catComment)
        }
        meal.updateStatus(MealStatus.COMPLETED)

        if (catRepository.existsByUserId(meal.userId)) {
            catService.updateLastMealAt(meal.userId, meal.mealAt)
        } else {
            logger.error("가입 시 생성되어야 할 고양이가 없어 마지막 식사 시각을 갱신하지 않습니다: userId={}", meal.userId)
        }

        return convertAnalysisPushResult(meal)
    }

    /**
     * 작업을 대기로 되돌리고 식사를 가져가기 전 상태로 되돌린다. 다음 polling에서 다시 가져간다.
     *
     * @return 이 Worker의 작업이라 되돌렸으면 true
     */
    @Transactional
    fun retryMealAnalysis(job: MealAnalysisJobResult): Boolean {
        if (mealAnalysisJobRepository.resetLease(job.jobId, job.leaseCount) == 0) {
            logger.info("다른 Worker가 가져간 분석 작업이라 되돌리지 않습니다: jobId={}, leaseCount={}", job.jobId, job.leaseCount)
            return false
        }

        resetMealStatus(job)
        return true
    }

    /**
     * 분석을 시작하지 못한 작업을 가져간 적 없는 것처럼 되돌린다. 번호를 하나 내려 시도 횟수를 쓰지 않으며,
     * 식사를 가져가기 전 상태로 되돌린다. [delay]가 지난 뒤의 polling에서 같은 번호로 다시 가져간다.
     *
     * 실행기가 가득 차 작업을 받지 못했을 때 쓴다. 설정 오류로 분석하지 못했을 때도 쓰는데, 이때는 시작한 작업이라
     * lease가 만료된 이전 Worker가 아직 돌고 있으면 내린 번호가 그 Worker의 번호와 같아져 결과를 쓸 수 있다(감수한 위험).
     *
     * @return 이 Worker의 작업이라 되돌렸으면 true
     */
    @Transactional
    fun cancelMealAnalysis(job: MealAnalysisJobResult, delay: Duration): Boolean {
        if (mealAnalysisJobRepository.cancelLease(job.jobId, job.leaseCount, delay.toSeconds()) == 0) {
            logger.info("다른 Worker가 가져간 분석 작업이라 되돌리지 않습니다: jobId={}, leaseCount={}", job.jobId, job.leaseCount)
            return false
        }

        resetMealStatus(job)
        return true
    }

    /**
     * 분석을 FAILED로 끝낸다. 작업은 지운다.
     *
     * @return 알림에 쓸 값. 이 Worker의 작업이 아니면 null
     */
    @Transactional
    fun failMealAnalysis(job: MealAnalysisJobResult): AnalysisPushResult? {
        val meal = deleteJobAndGetMeal(job) ?: return null

        meal.updateStatus(MealStatus.FAILED)

        return convertAnalysisPushResult(meal)
    }

    /**
     * 작업이 아직 이 Worker의 것이면 지우고, 식사를 잠가 돌려준다. 작업 → 식사 순서로 잠근다.
     * 지운 행이 없으면 다른 Worker가 가져갔거나 이미 끝난 작업이라 null이다.
     */
    private fun deleteJobAndGetMeal(job: MealAnalysisJobResult): Meal? {
        if (mealAnalysisJobRepository.deleteByIdAndLeaseCount(job.jobId, job.leaseCount) == 0) {
            logger.info("다른 Worker가 가져간 분석 작업이라 결과를 쓰지 않습니다: jobId={}, leaseCount={}", job.jobId, job.leaseCount)
            return null
        }

        return mealRepository.findMealWithLockById(job.mealId)
            ?: run {
                logger.error("분석 작업의 식사가 없습니다: jobId={}, mealId={}", job.jobId, job.mealId)
                null
            }
    }

    /**
     * 대기로 되돌린 작업의 식사를 가져가기 전 상태로 되돌린다.
     * 인식 작업의 식사는 가져갈 때 RECOGNIZING 그대로 두었으므로 바꿀 것이 없고, 그 밖의 식사는 ANALYZING에서 WAITING으로 되돌린다.
     */
    private fun resetMealStatus(job: MealAnalysisJobResult) {
        if (job.category == MealAnalysisJobCategory.RECOGNITION) {
            return
        }

        mealRepository.updateMealStatuses(listOf(job.mealId), MealStatus.WAITING, Instant.now(clock))
    }

    private fun convertAnalysisPushResult(meal: Meal) = AnalysisPushResult(
        mealId = meal.id,
        userId = meal.userId,
        mealName = meal.name,
        status = meal.status,
        isDeleted = meal.deletedAt != null,
    )
}
