package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.ai.AnalysisImageClient
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisClient
import com.dandi.nyummy.infra.ai.nutrition.ServingNutritionClient
import com.dandi.nyummy.infra.ai.recognition.MealRecognitionClient
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.meal.calculator.calculateHintedServingNutrition
import com.dandi.nyummy.meal.calculator.calculateMealFoodNutrition
import com.dandi.nyummy.meal.calculator.calculateTotalAdjustedServingNutritions
import com.dandi.nyummy.meal.calculator.calculateTotalNutrition
import com.dandi.nyummy.meal.calculator.matchesNutritionHint
import com.dandi.nyummy.meal.calculator.pickRewardBox
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.CatCommentMealFoodInput
import com.dandi.nyummy.meal.dto.NutritionHint
import com.dandi.nyummy.meal.dto.PhotoServingNutritionInput
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.enum.PackageNutritionMismatchCategory
import com.dandi.nyummy.meal.service.FoodNutritionService
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.worker.config.ConditionalOnAnalysisWorkerEnabled
import com.dandi.nyummy.worker.dto.AnalysisMealFoodResult
import com.dandi.nyummy.worker.dto.AnalysisMealResult
import com.dandi.nyummy.worker.dto.AnalysisPushResult
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

@Service
@ConditionalOnAnalysisWorkerEnabled
class AnalysisService(
    private val nutritionAnalysisClient: NutritionAnalysisClient,
    private val analysisImageClient: AnalysisImageClient,
    private val mealRecognitionClient: MealRecognitionClient,
    private val servingNutritionClient: ServingNutritionClient,
    private val foodNutritionService: FoodNutritionService,
    private val catService: CatService,
    private val mealAnalysisJobTransactionService: MealAnalysisJobTransactionService,
    private val mealProperties: MealProperties,
    private val catRepository: CatRepository,
    private val notificationService: NotificationService,
    private val appLinkProperties: AppLinkProperties,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    private val messageSource: MessageSource,
    private val random: Random,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AnalysisService::class.java)

        // 설정 오류로 되돌린 작업을 다시 가져가기까지 기다리는 시간. 설정이 고장 난 동안 Gemini를 1초마다 부르지 않게 한다.
        private val MISCONFIGURED_DELAY = Duration.ofMinutes(1)

        // 분석 시도 한 번의 결과와 걸린 시간(S3 다운로드 + Gemini + 결과 저장). job.category 태그로 작업 종류를 나눈다.
        private const val ANALYSIS_METER = "nyummy.meal.analysis"

        // 작업을 가져갈 수 있게 된 때부터 끝날 때까지 걸린 시간. 사용자가 결과를 기다린 시간이다.
        // 미루지 않은 작업은 쌓인 때부터이고, 사용자가 음식 목록을 고칠 시간을 기다린 영양 작업은 그 시간이 지난 때부터다.
        // 인식 작업은 식사 기록부터 음식 목록이 준비될 때까지(outcome이 recognized)다.
        private const val ANALYSIS_ELAPSED_METER = "nyummy.meal.analysis.elapsed"

        // 작업 종류를 담는 태그. 이름을 job으로 두면 지표를 받는 쪽(Prometheus)이 서비스 이름으로 만드는 job 라벨과 겹쳐
        // 이 태그가 덮인다. 그러면 작업 종류가 모두 서비스 이름 하나로 보여 종류별로 나눌 수 없다.
        private const val JOB_CATEGORY_TAG = "job.category"

        // 종류가 없는 작업(v1 분석)의 job.category 태그 값
        private const val V1_JOB = "v1"

        // 분류된 실패가 아닌 예외의 category 태그 값
        private const val UNKNOWN_CATEGORY = "unknown"
    }

    /**
     * Worker가 lease로 가져간 작업 한 건을 작업 종류에 맞게 처리하고 결과를 알린다. 실행기 스레드에서 돈다.
     * 인식 작업은 음식을 인식해 음식 줄을 저장하고, 영양 작업은 음식 줄의 영양을 구해 식사를 완료하고,
     * 종류가 없는 작업은 v1 분석을 한다. 실패를 다루는 규칙은 종류와 상관없이 같다.
     *
     * Gemini 호출은 트랜잭션 밖에서 하고, 결과는 [MealAnalysisJobTransactionService]의 짧은 트랜잭션으로 남긴다.
     * - 성공하면 COMPLETED로 확정하고 알린다. 인식 작업은 음식 줄을 저장하고 영양 작업으로 넘기며 알리지 않는다.
     * - 요청 한도 초과·서버 오류·타임아웃처럼 일시적인 실패는 재시도할 수 있으면 대기로 되돌리고 알리지 않는다.
     * - 키·권한 같은 설정 오류는 시도 횟수를 쓰지 않고 잠시 뒤로 되돌리고 알리지 않는다. 설정을 고치면 그대로 처리된다.
     * - 그 밖의 실패(재시도를 다 쓴 일시적 실패, 음식 아님, 응답 해석 실패, 거부, 분류에 없는 예외)는 FAILED로 끝낸다.
     *
     * lease가 만료돼 다른 Worker가 같은 작업을 가져갔으면 결과를 쓰지 않고 알리지도 않는다.
     * 분석하는 동안 사용자가 식사를 지웠으면 결과는 남기고 알림만 건너뛴다.
     */
    fun analyzeMealAnalysisJob(job: MealAnalysisJobResult) {
        // 이 시도를 식별하는 값. 재시도가 또 실패해도 앞선 실패와 다른 알림이 되게 한다.
        val analyzedAt = Instant.now(clock)

        try {
            when (job.category) {
                MealAnalysisJobCategory.RECOGNITION -> recognizeMeal(job, analyzedAt)
                MealAnalysisJobCategory.NUTRITION -> analyzeMealFoods(job, analyzedAt)
                null -> analyzeNutrition(job, analyzedAt)
            }
        } catch (e: AnalysisFailureException) {
            handleAnalysisFailure(job, e, analyzedAt)
        } catch (e: RuntimeException) {
            logger.error("영양 분석 중 예상하지 못한 오류로 실패합니다: mealId={}, leaseCount={}", job.mealId, job.leaseCount, e)
            val pushResult = mealAnalysisJobTransactionService.failMealAnalysis(job)
            if (pushResult != null) {
                recordAnalysis(job, AnalysisOutcome.FAILED, UNKNOWN_CATEGORY, analyzedAt)
            }
            sendAnalysisPush(pushResult, null, analyzedAt, job.language)
        }
    }

    /** 사진 한 장에서 이름·영양·코멘트를 한 번에 구해 COMPLETED로 확정하고 알린다(v1 분석). */
    private fun analyzeNutrition(job: MealAnalysisJobResult, analyzedAt: Instant) {
        val analysisResult = nutritionAnalysisClient.analyzeNutrition(job.imageKey, job.language)
        val pushResult = mealAnalysisJobTransactionService.confirmMealAnalysis(job, analysisResult)
        if (pushResult != null) {
            recordAnalysis(job, AnalysisOutcome.COMPLETED, null, analyzedAt)
        }
        sendAnalysisPush(pushResult, null, analyzedAt, job.language)
    }

    /**
     * 식사 기록 요청 안에서 끝내지 못한 음식 인식을 한다. 사진을 받아 인식하고, 음식 줄을 저장한 뒤 영양 작업으로 넘긴다.
     *
     * 식사 기록 보상 상자를 뽑아 함께 넘긴다. 기록할 때는 음식인지 몰라 주지 않았으므로, 음식으로 인식한 지금 준다.
     * 이미 받은 식사인지는 식사를 잠가 읽는 [MealAnalysisJobTransactionService.confirmMealRecognition]이 가린다.
     *
     * 인식이 끝났다는 알림은 보내지 않는다. 사용자는 대기 화면에서 상태를 조회하다 음식 목록으로 넘어간다.
     * 음식이 아니거나 인식에 실패한 것은 [handleAnalysisFailure]가 다른 작업과 같은 규칙으로 다루고 알린다.
     */
    private fun recognizeMeal(job: MealAnalysisJobResult, analyzedAt: Instant) {
        val image = downloadImage(job)

        val recognition = mealRecognitionClient.recognizeMeal(image.bytes, image.contentType, job.language)
        val isConfirmed = mealAnalysisJobTransactionService.confirmMealRecognition(
            job = job,
            recognition = recognition,
            nutritionDelay = mealProperties.recognition.nutritionDelay,
            rewardBox = pickRewardBox(mealProperties.rewardBoxes, random),
            rewardMailExpiresAt = Instant.now(clock).plus(mealProperties.rewardMailTimeToLive),
        )
        if (isConfirmed) {
            recordAnalysis(job, AnalysisOutcome.RECOGNIZED, null, analyzedAt)
        }
    }

    /**
     * 음식 줄의 영양을 구해 식사를 COMPLETED로 확정하고 알린다. 인식한 뒤 기다린 시간이 지났거나 사용자가 음식 목록을 보냈을 때 돈다.
     *
     * 1인분 조회와 코멘트는 트랜잭션 밖에서 하고, 결과는 한 트랜잭션으로 저장한다. 그래서 COMPLETED인 식사에는 늘 코멘트가 있다.
     * 코멘트는 식사에 아직 없을 때만 만든다. 만들지 못하면 [CatService.createCatComment]가 고정 문구를 돌려주므로
     * 코멘트 때문에 영양 작업이 실패하지는 않는다.
     *
     * 완료 알림은 처음 계산이 끝났을 때만 보낸다. 이미 한 번 완료됐던 식사를 사용자가 고쳐 다시 계산한 것은 알리지 않는다.
     * 방금 앱에서 직접 고친 것이라 화면에서 결과를 보고 있고, 고칠 때마다 알림이 가면 성가시다.
     * 코멘트는 식사당 한 번만 만들므로, 이번 계산에서 코멘트를 만들었는지로 처음 계산인지를 가린다.
     * 실패 알림은 다시 계산한 것에도 보낸다. 완료였던 식사가 FAILED가 된 것은 알려야 한다.
     */
    private fun analyzeMealFoods(job: MealAnalysisJobResult, analyzedAt: Instant) {
        val meal = mealAnalysisJobTransactionService.getAnalysisMeal(job)
            ?: run {
                logger.error("영양 작업의 식사가 없습니다: jobId={}, mealId={}", job.jobId, job.mealId)
                return
            }
        if (meal.mealFoods.isEmpty()) {
            throw AnalysisFailureException(
                AnalysisFailureCategory.INVALID_REQUEST,
                "영양을 계산할 음식 줄이 없습니다: mealId=${job.mealId}",
            )
        }

        val servingNutritions = getServingNutritions(job, meal)
        val catComment = if (meal.hasCatComment) null else createCatComment(meal, servingNutritions, job.language)

        val pushResult = mealAnalysisJobTransactionService.confirmMealNutrition(job, servingNutritions, catComment)
        if (pushResult != null) {
            recordAnalysis(job, AnalysisOutcome.COMPLETED, null, analyzedAt)
        }
        if (!meal.hasCatComment) {
            sendAnalysisPush(pushResult, null, analyzedAt, job.language)
        }
    }

    /**
     * 1인분 영양이나 아이콘이 비어 있는 줄만 모아 조회한다. 비어 있는 줄이 없으면 모델을 부르지 않는다.
     * 인분만 바꾼 식사나 사용자가 영양과 아이콘을 모두 정한 식사가 그렇다.
     *
     * 줄은 둘로 나눠 구한다.
     * - 음식 사진 식사에서 인식이 사진에서 찾은 줄은 사진을 함께 보내 1인분과 인분을 구한다([getPhotoServingNutritions]).
     *   사용자가 이름을 고친 줄도 사진에 있는 음식이므로 여기로 간다.
     * - 그 밖의 줄(음식 사진이 아닌 유형의 줄, 사용자가 더한 줄)은 이름만으로 구한다([getNamedServingNutritions]).
     *
     * @return 음식 줄 ID별 조회 결과. 조회하지 않은 줄은 없다
     */
    private fun getServingNutritions(
        job: MealAnalysisJobResult,
        meal: AnalysisMealResult,
    ): Map<Long, ServingNutritionResult> {
        val mealFoods = meal.mealFoods.filter { it.servingNutrition == null || it.iconId == null }
        val (photoMealFoods, namedMealFoods) = mealFoods.partition {
            meal.inputCategory == MealInputCategory.FOOD_PHOTO && it.isRecognized
        }

        return getPhotoServingNutritions(job, meal, photoMealFoods) +
            getNamedServingNutritions(job, meal, namedMealFoods)
    }

    /**
     * 사진을 함께 보내 줄마다 1인분, 인분, 아이콘을 구한다.
     *
     * 사진에 식사 전체의 값이 적혀 있으면 줄들의 합이 그 값과 같아지게 1인분을 맞춘다
     * ([calculateTotalAdjustedServingNutritions]). 식사에서 인식한 줄을 이번에 모두 구할 때만 맞춘다.
     * 일부 줄만 다시 구할 때(계산이 끝난 식사에서 한 줄의 이름을 고친 경우)는 그 줄만 전체 값에 맞출 수 없어 구한 값 그대로 쓴다.
     *
     * 여기서 구한 값은 음식 이름으로 저장하지 않고, 저장해 둔 값을 찾지도 않는다. 그 사진 속 음식의 재료와 양을 보고 구한 값이라
     * 같은 이름의 다른 음식에 다시 쓸 수 없고, 인분은 사진을 봐야 구할 수 있다.
     */
    private fun getPhotoServingNutritions(
        job: MealAnalysisJobResult,
        meal: AnalysisMealResult,
        mealFoods: List<AnalysisMealFoodResult>,
    ): Map<Long, ServingNutritionResult> {
        if (mealFoods.isEmpty()) {
            return emptyMap()
        }
        val image = downloadImage(job)
        val result = servingNutritionClient.getPhotoServingNutritions(
            imageBytes = image.bytes,
            contentType = image.contentType,
            foods = mealFoods.map { PhotoServingNutritionInput(it.name, it.nutritionHint) },
        )

        val totalNutritionHint = result.totalNutritionHint
        val hasAllRecognizedMealFoods = mealFoods.size == meal.mealFoods.count { it.isRecognized }
        val servingNutritions = if (totalNutritionHint != null && hasAllRecognizedMealFoods) {
            calculateTotalAdjustedServingNutritions(
                servings = mealFoods.zip(result.servingNutritions) { mealFood, servingNutritionResult ->
                    servingNutritionResult.servingNutrition to calculatePortion(mealFood, servingNutritionResult)
                },
                totalNutritionHint = totalNutritionHint,
            )
        } else {
            result.servingNutritions.map { it.servingNutrition }
        }

        return mealFoods.indices.associate { index ->
            mealFoods[index].mealFoodId to
                result.servingNutritions[index].copy(servingNutrition = servingNutritions[index])
        }
    }

    /**
     * 이름만으로 줄마다 1인분과 아이콘을 구한다. 사진에 칼로리가 적혀 있던 줄은 구한 값을 그 칼로리에 맞춘다
     * ([calculateHintedServingNutrition]).
     *
     * 제품 유형이 아니면 음식 이름으로 저장해 둔 값을 먼저 찾는다([getFoodNutritions]). 제품 유형은 이름이 제품 이름이라
     * 그 값을 쓰지 않고, 제품 이름과 용량으로 저장해 둔 표시값을 먼저 찾는다([getPackageNutritions]).
     */
    private fun getNamedServingNutritions(
        job: MealAnalysisJobResult,
        meal: AnalysisMealResult,
        mealFoods: List<AnalysisMealFoodResult>,
    ): Map<Long, ServingNutritionResult> {
        if (mealFoods.isEmpty()) {
            return emptyMap()
        }
        val names = mealFoods.map { it.name }
        val servingNutritionResults = if (meal.inputCategory.isPackage) {
            getPackageNutritions(job, meal.inputCategory, mealFoods)
        } else {
            getFoodNutritions(names, meal.inputCategory)
        }

        return mealFoods.zip(servingNutritionResults).associate { (mealFood, servingNutritionResult) ->
            val nutritionHint = mealFood.nutritionHint
            val servingNutrition = if (nutritionHint == null) {
                servingNutritionResult.servingNutrition
            } else {
                calculateHintedServingNutrition(nutritionHint, servingNutritionResult.servingNutrition)
            }

            mealFood.mealFoodId to servingNutritionResult.copy(servingNutrition = servingNutrition)
        }
    }

    /**
     * 이름마다 저장해 둔 1인분과 아이콘을 먼저 찾고, 없는 이름만 모아 모델에 물은 뒤 그 값을 저장한다.
     * 저장해 둔 이름은 모델을 부르지 않으므로, 같은 이름은 유효 기간 동안 어느 식사에서나 같은 값이 나온다.
     *
     * 같은 이름의 줄이 여럿이면 한 번만 찾고 한 번만 묻는다. 따로 물으면 같은 식사 안에서 같은 이름의 값이 달라질 수 있다.
     * 저장하는 값은 모델이 낸 그대로다. 사진에 적혀 있던 칼로리에 맞추는 것은 줄마다 다른 값이라 저장한 뒤에 한다.
     *
     * @return [names]와 같은 순서, 같은 개수의 결과
     */
    private fun getFoodNutritions(
        names: List<String>,
        inputCategory: MealInputCategory,
    ): List<ServingNutritionResult> {
        val savedResults = names.distinct().associateWith { foodNutritionService.getFoodNutrition(it) }
        val missingNames = savedResults.filterValues { it == null }.keys.toList()
        val createdResults = if (missingNames.isEmpty()) {
            emptyMap()
        } else {
            missingNames.zip(servingNutritionClient.getNamedServingNutritions(missingNames, inputCategory)).toMap()
        }
        createdResults.forEach { (name, result) -> foodNutritionService.createFoodNutrition(name, result) }

        return names.map { savedResults[it] ?: createdResults.getValue(it) }
    }

    /**
     * 제품 유형의 줄마다 제품 하나의 값과 아이콘을 구한다. 제품 이름과 용량으로 저장해 둔 표시값을 먼저 찾고,
     * 없는 제품만 모아 모델에 묻는다. 모델에는 이름 뒤에 용량을 붙여 보내 그 용량의 값을 답하게 한다.
     *
     * 저장해 둔 값은 제품명과 함께 사진에서 읽은 표시값뿐이다. 그래서 줄에 그런 사진 값이 있을 때만 저장하거나 바꾼다
     * ([convertNutritionHint]). 제품명을 읽지 못한 줄과 사용자가 영양을 직접 적은 줄은 저장해 둔 값을 찾아 쓰기만 한다.
     * - 저장해 둔 값이 있으면 모델을 부르지 않는다. 사진 값이 그 값과 다르면 기록하고, 네 값이 다 적힌 사진이면 그 값으로 바꾼다
     *   ([updateSavedPackageNutrition]).
     * - 저장해 둔 값이 없고 네 값이 다 적힌 사진이면 그 값을 저장한다([createPackageNutrition]). 아이콘은 모델이 고른 것이다.
     * - 사진에 일부만 적혀 있거나 아무것도 없던 줄은 모델 값으로 채우고 저장하지 않는다. 모델 값은 표시값과 많이 다르다.
     *
     * 이름과 용량이 같은 줄이 여럿이면 한 번만 찾고 한 번만 묻는다.
     *
     * @return [mealFoods]와 같은 순서, 같은 개수의 결과. 줄에 이미 있는 값(사진 값, 사용자가 적은 값)은 부르는 쪽이 그대로 둔다
     */
    private fun getPackageNutritions(
        job: MealAnalysisJobResult,
        inputCategory: MealInputCategory,
        mealFoods: List<AnalysisMealFoodResult>,
    ): List<ServingNutritionResult> {
        val packageMealFoods = mealFoods.distinctBy { createPackageKey(it) }
        val savedResults = packageMealFoods.associate {
            createPackageKey(it) to foodNutritionService.getPackageNutrition(it.name, it.netContent, it.netContentUnit)
        }
        val missingMealFoods = packageMealFoods.filter { savedResults[createPackageKey(it)] == null }
        val namedResults = if (missingMealFoods.isEmpty()) {
            emptyMap()
        } else {
            missingMealFoods.map { createPackageKey(it) }
                .zip(
                    servingNutritionClient.getNamedServingNutritions(
                        missingMealFoods.map { createPackageName(it) },
                        inputCategory,
                    ),
                )
                .toMap()
        }

        return mealFoods.map { mealFood ->
            val packageKey = createPackageKey(mealFood)
            val nutritionHint = convertNutritionHint(mealFood)
            val savedResult = savedResults[packageKey]
            if (savedResult != null) {
                nutritionHint?.let { updateSavedPackageNutrition(job, mealFood, it, savedResult) }
                return@map savedResult
            }

            val namedResult = namedResults.getValue(packageKey)
            nutritionHint?.let { createPackageNutrition(job, mealFood, it, namedResult) }
            namedResult
        }
    }

    /** 같은 제품인지 가리는 값. 이름과 용량이 모두 같아야 같은 제품이다. */
    private fun createPackageKey(mealFood: AnalysisMealFoodResult) =
        Triple(mealFood.name, mealFood.netContent, mealFood.netContentUnit)

    /** 모델에 물을 제품 이름. 용량을 읽은 제품은 이름 뒤에 용량을 붙인다(예: "셀렉스 프로핏 250ml"). */
    private fun createPackageName(mealFood: AnalysisMealFoodResult): String {
        val netContent = mealFood.netContent ?: return mealFood.name
        val netContentUnit = mealFood.netContentUnit ?: return mealFood.name

        return "${mealFood.name} ${netContent.stripTrailingZeros().toPlainString()}${netContentUnit.name.lowercase()}"
    }

    /**
     * 이 줄에 사진에서 읽어 둔 값. 네 값이 다 적혀 있었으면 넷 모두, 일부만 적혀 있었으면 그 항목만 담는다.
     *
     * 사진 값을 따로 표시해 두지 않으므로 줄의 상태로 가린다. 여기에 오는 줄은 1인분이나 아이콘이 비어 있는 줄이고,
     * 모델이 값을 구한 줄은 아이콘도 함께 채워져 다시 오지 않는다. 그래서 값이 있는 채로 오는 줄은 사진 값이 적힌 줄이거나
     * 사용자가 영양을 직접 적은 줄이다. 사용자가 적은 값은 먹은 양에 맞춘 값이거나 어림값일 수 있어 사진 값으로 치지 않는다.
     *
     * 이름이 포장에서 읽은 제품명이 아닌 줄의 값도 치지 않는다. 영양성분표 면만 찍은 사진은 제품명이 없어 그 면의 문구나
     * 종류("단백질 음료")가 이름이 되는데, 그 이름으로 값을 저장하면 다른 제품의 사진에도 맞아 틀린 값을 준다.
     *
     * @return 저장하거나 비교할 사진 값이 없으면 null
     */
    private fun convertNutritionHint(mealFood: AnalysisMealFoodResult): NutritionHint? {
        if (mealFood.isNutritionFixed || !mealFood.hasPackageName) {
            return null
        }

        return mealFood.nutritionHint
            ?: mealFood.servingNutrition?.let { NutritionHint(it.calory, it.carbs, it.protein, it.fat) }
    }

    /**
     * 저장해 둔 제품의 값이 이번 사진 값과 다르면 기록하고, 네 값이 다 적힌 사진이면 그 값으로 바꾼다.
     * 유효 기간이 없는 값이라, 잘못 들어간 값이 고쳐지고 바뀐 제품을 따라가는 길이 이것뿐이다. 바꾸기 전의 값은 기록에 남는다.
     * 일부만 적힌 사진은 기록만 한다. 빈 항목을 채울 표시값이 없다.
     */
    private fun updateSavedPackageNutrition(
        job: MealAnalysisJobResult,
        mealFood: AnalysisMealFoodResult,
        nutritionHint: NutritionHint,
        savedResult: ServingNutritionResult,
    ) {
        if (matchesNutritionHint(nutritionHint, savedResult.servingNutrition)) {
            return
        }

        foodNutritionService.createPackageNutritionMismatch(
            category = PackageNutritionMismatchCategory.SAVED,
            name = mealFood.name,
            netContent = mealFood.netContent,
            netContentUnit = mealFood.netContentUnit,
            nutritionHint = nutritionHint,
            servingNutrition = savedResult.servingNutrition,
            mealId = job.mealId,
        )
        mealFood.servingNutrition?.let {
            foodNutritionService.updatePackageNutrition(mealFood.name, mealFood.netContent, mealFood.netContentUnit, it)
        }
    }

    /**
     * 처음 보는 제품의 사진 값을 저장한다. 네 값이 다 적힌 사진만 저장하고, 아이콘은 모델이 고른 것을 쓴다.
     * 모델이 구한 값이 사진 값과 허용 오차를 넘게 달라도 사진 값을 저장하고, 달랐다는 것만 기록한다.
     *
     * 모델이 먹거나 마시는 것이 아니라고 본 이름은 저장하지 않는다. 제품 이름을 제대로 읽지 못한 것일 수 있다.
     */
    private fun createPackageNutrition(
        job: MealAnalysisJobResult,
        mealFood: AnalysisMealFoodResult,
        nutritionHint: NutritionHint,
        namedResult: ServingNutritionResult,
    ) {
        val servingNutrition = mealFood.servingNutrition ?: return
        if (!namedResult.isFood) {
            return
        }

        foodNutritionService.createPackageNutrition(
            name = mealFood.name,
            netContent = mealFood.netContent,
            netContentUnit = mealFood.netContentUnit,
            servingNutrition = servingNutrition,
            iconId = namedResult.iconId,
        )
        if (!matchesNutritionHint(
                nutritionHint,
                namedResult.servingNutrition,
                mealProperties.packageNutrition.tolerance,
            )
        ) {
            foodNutritionService.createPackageNutritionMismatch(
                category = PackageNutritionMismatchCategory.NAMED,
                name = mealFood.name,
                netContent = mealFood.netContent,
                netContentUnit = mealFood.netContentUnit,
                nutritionHint = nutritionHint,
                servingNutrition = namedResult.servingNutrition,
                mealId = job.mealId,
            )
        }
    }

    /** 분석할 사진을 받는다. 콘텐츠 타입이 없으면 모델에 넘길 수 없다. */
    private fun downloadImage(job: MealAnalysisJobResult): AnalysisImage {
        val image = analysisImageClient.downloadImage(job.imageKey)
        val contentType = image.contentType
            ?: throw AnalysisFailureException(
                AnalysisFailureCategory.INVALID_REQUEST,
                "분석할 이미지의 콘텐츠 타입이 없습니다: ${job.imageKey}",
            )

        return AnalysisImage(image.bytes, contentType)
    }

    /**
     * 줄에 저장될 1인분. 이미 있으면 그 값이고, 없으면 조회한 값으로 빈 항목만 채운 값이다.
     * 저장할 때 채우는 규칙(`MealFood.updateEmptyServingNutrition`)과 같다.
     */
    private fun calculateServingNutrition(
        mealFood: AnalysisMealFoodResult,
        servingNutritionResult: ServingNutritionResult?,
    ): ServingNutrition {
        mealFood.servingNutrition?.let { return it }
        val servingNutrition = checkNotNull(servingNutritionResult) {
            "1인분 값이 없는 음식 줄을 조회하지 않았습니다: mealFoodId=${mealFood.mealFoodId}"
        }.servingNutrition
        val nutritionHint = mealFood.nutritionHint ?: return servingNutrition

        return ServingNutrition(
            calory = nutritionHint.calory ?: servingNutrition.calory,
            carbs = nutritionHint.carbs ?: servingNutrition.carbs,
            protein = nutritionHint.protein ?: servingNutrition.protein,
            fat = nutritionHint.fat ?: servingNutrition.fat,
        )
    }

    /**
     * 줄에 저장될 인분. 이미 있으면 그 값이고, 없으면 조회 결과의 인분, 그것도 없으면 [MealFood.DEFAULT_PORTION]이다.
     * 저장할 때 채우는 규칙(`MealFoodService.updateServingNutritions`)과 같다.
     */
    private fun calculatePortion(
        mealFood: AnalysisMealFoodResult,
        servingNutritionResult: ServingNutritionResult?,
    ): BigDecimal = mealFood.portion ?: servingNutritionResult?.portion ?: MealFood.DEFAULT_PORTION

    /**
     * 저장하기 전의 값으로 줄 영양과 식사 합계를 먼저 구해 코멘트를 만든다.
     * 1인분과 인분은 저장될 값 그대로 구한다([calculateServingNutrition], [calculatePortion]).
     */
    private fun createCatComment(
        meal: AnalysisMealResult,
        servingNutritions: Map<Long, ServingNutritionResult>,
        language: Language,
    ): String {
        val servings = meal.mealFoods.map { mealFood ->
            val servingNutritionResult = servingNutritions[mealFood.mealFoodId]
            calculateServingNutrition(mealFood, servingNutritionResult) to
                calculatePortion(mealFood, servingNutritionResult)
        }

        return catService.createCatComment(
            userId = meal.userId,
            mealAt = meal.mealAt,
            mealFoods = meal.mealFoods.zip(servings) { mealFood, (servingNutrition, portion) ->
                CatCommentMealFoodInput(
                    name = mealFood.name,
                    portion = portion,
                    nutrition = calculateMealFoodNutrition(servingNutrition, portion),
                )
            },
            nutrition = calculateTotalNutrition(servings),
            language = language,
        )
    }

    /**
     * 분류된 실패를 재시도할지 FAILED로 끝낼지 정하고, FAILED로 끝냈으면 알린다.
     *
     * 일시적인 실패(UNAVAILABLE)는 재시도할 수 있으면 대기로 되돌리고 알리지 않는다. 마지막 시도였으면 FAILED로 끝낸다.
     * 설정 오류(MISCONFIGURED)는 사용자 탓이 아니고 모든 작업이 같이 실패하므로, FAILED로 끝내지도 알리지도 않고
     * 번호를 내려 [MISCONFIGURED_DELAY] 뒤에 다시 가져가게 한다.
     * Worker가 종료될 때 대기 시간 안에 끝나지 않은 작업도 같은 규칙으로 정리한다.
     */
    fun handleAnalysisFailure(job: MealAnalysisJobResult, failure: AnalysisFailureException) {
        handleAnalysisFailure(job, failure, Instant.now(clock))
    }

    // 기본 인자로 시각을 받지 않는다. Kotlin은 기본 인자 호출을 정적 보조 메서드로 바꿔 mock으로 가로챌 수 없게 된다.
    private fun handleAnalysisFailure(
        job: MealAnalysisJobResult,
        failure: AnalysisFailureException,
        analyzedAt: Instant,
    ) {
        when (failure.category) {
            AnalysisFailureCategory.UNAVAILABLE ->
                if (job.canRetry) {
                    logger.warn(
                        "영양 분석이 일시적으로 실패해 다시 시도합니다: mealId={}, leaseCount={}, {}",
                        job.mealId,
                        job.leaseCount,
                        failure.message,
                    )
                    if (mealAnalysisJobTransactionService.retryMealAnalysis(job)) {
                        recordAnalysis(job, AnalysisOutcome.RETRIED, failure.category, analyzedAt)
                    }
                    return
                } else {
                    logger.error("영양 분석이 재시도 후에도 실패했습니다: mealId={}, leaseCount={}", job.mealId, job.leaseCount, failure)
                }

            AnalysisFailureCategory.NOT_FOOD ->
                logger.info("음식 사진이 아니라 분석을 실패로 끝냅니다: mealId={}", job.mealId)

            AnalysisFailureCategory.INVALID_RESPONSE, AnalysisFailureCategory.INVALID_REQUEST ->
                logger.error("영양 분석에 실패했습니다: mealId={}, category={}", job.mealId, failure.category, failure)

            AnalysisFailureCategory.MISCONFIGURED -> {
                logger.error(
                    "설정 오류로 영양 분석을 하지 못해 잠시 뒤 다시 시도합니다: mealId={}, leaseCount={}",
                    job.mealId,
                    job.leaseCount,
                    failure,
                )
                if (mealAnalysisJobTransactionService.cancelMealAnalysis(job, MISCONFIGURED_DELAY)) {
                    recordAnalysis(job, AnalysisOutcome.DELAYED, failure.category, analyzedAt)
                }
                return
            }
        }
        val pushResult = mealAnalysisJobTransactionService.failMealAnalysis(job)
        if (pushResult != null) {
            recordAnalysis(job, AnalysisOutcome.FAILED, failure.category, analyzedAt)
        }
        sendAnalysisPush(pushResult, failure.category, analyzedAt, job.language)
    }

    private fun recordAnalysis(
        job: MealAnalysisJobResult,
        outcome: AnalysisOutcome,
        category: AnalysisFailureCategory?,
        analyzedAt: Instant,
    ) {
        recordAnalysis(job, outcome, category?.name?.lowercase() ?: "none", analyzedAt)
    }

    /**
     * 이 Worker가 결과를 실제로 남긴 시도만 기록한다. 다른 Worker가 가져가 아무것도 쓰지 못한 시도는 세지 않는다.
     * 작업이 끝났으면(COMPLETED·RECOGNIZED·FAILED) 작업을 가져갈 수 있게 된 때부터 걸린 시간도 기록한다.
     */
    private fun recordAnalysis(
        job: MealAnalysisJobResult,
        outcome: AnalysisOutcome,
        category: String,
        analyzedAt: Instant,
    ) {
        val now = Instant.now(clock)
        val jobTag = job.category?.name?.lowercase() ?: V1_JOB
        Timer.builder(ANALYSIS_METER)
            .tag(JOB_CATEGORY_TAG, jobTag)
            .tag("outcome", outcome.tagValue)
            .tag("category", category)
            .register(meterRegistry)
            .record(Duration.between(analyzedAt, now))

        if (outcome.isFinal) {
            Timer.builder(ANALYSIS_ELAPSED_METER)
                .tag(JOB_CATEGORY_TAG, jobTag)
                .tag("outcome", outcome.tagValue)
                .register(meterRegistry)
                .record(Duration.between(job.availableAt, now))
        }
    }

    /**
     * 분석 결과를 푸시로 알린다. 상태가 이미 커밋된 뒤에 보내므로 발송이 실패해도 결과는 남는다.
     * 수신 거부·토큰 없음은 [NotificationService.sendServicePush]가 조용히 넘긴다.
     */
    private fun sendAnalysisPush(
        pushResult: AnalysisPushResult?,
        failureCategory: AnalysisFailureCategory?,
        analyzedAt: Instant,
        language: Language,
    ) {
        // 이 Worker의 작업이 아니었으면(다른 Worker가 가져갔으면) 결과를 쓰지 않았으므로 알리지도 않는다.
        if (pushResult == null) {
            return
        }
        if (pushResult.isDeleted) {
            logger.info("분석하는 동안 지워진 식사라 알림을 보내지 않습니다: mealId={}", pushResult.mealId)
            return
        }

        try {
            val catName = catRepository.findByUserId(pushResult.userId)?.name
                ?: run {
                    logger.error("가입 시 생성되어야 할 고양이가 없습니다: userId={}", pushResult.userId)
                    return
                }

            val result = notificationService.sendServicePush(
                pushResult.userId,
                convertPushMessage(pushResult, failureCategory, catName, analyzedAt, language),
            )

            notificationService.deleteInvalidDeviceTokens(result.invalidTokens)
        } catch (e: RuntimeException) {
            logger.error("분석 결과 알림 발송 실패: mealId={}, status={}", pushResult.mealId, pushResult.status, e)
        }
    }

    /**
     * 성공·실패에 따라 알림 내용을 고른다. 음식 사진이 아니어서 실패했으면 다시 찍어 달라고 따로 안내한다.
     *
     * 링크는 모두 식사 상세다. 성공은 칼로리를 확인하러, 실패는 재시도하러 같은 화면으로 간다.
     * 문구는 식사를 기록한 요청의 언어로 고른다.
     */
    private fun convertPushMessage(
        pushResult: AnalysisPushResult,
        failureCategory: AnalysisFailureCategory?,
        catName: String,
        analyzedAt: Instant,
        language: Language,
    ): PushMessage {
        val notificationId = NotificationId.createAnalysisId(pushResult.mealId, pushResult.status, analyzedAt)
        val appLink = appLinkProperties.createMealDetailLink(pushResult.mealId)

        return when {
            pushResult.status == MealStatus.COMPLETED -> PushMessage(
                userId = pushResult.userId,
                type = PushType.ANALYSIS_COMPLETED,
                notificationId = notificationId,
                title = messageSource.getMessage("push.analysis.completed.title", language, catName),
                body = messageSource.getMessage("push.analysis.completed.body", language, pushResult.mealName),
                appLink = appLink,
            )

            failureCategory == AnalysisFailureCategory.NOT_FOOD -> PushMessage(
                userId = pushResult.userId,
                type = PushType.ANALYSIS_FAILED,
                notificationId = notificationId,
                title = messageSource.getMessage("push.analysis.notFood.title", language),
                body = messageSource.getMessage("push.analysis.notFood.body", language),
                appLink = appLink,
            )

            // 재시도까지 다 실패한 일시적인 실패. 사진 탓이 아니므로 잠시 뒤에 다시 하라고 알린다.
            failureCategory == AnalysisFailureCategory.UNAVAILABLE -> PushMessage(
                userId = pushResult.userId,
                type = PushType.ANALYSIS_FAILED,
                notificationId = notificationId,
                title = messageSource.getMessage("push.analysis.unavailable.title", language),
                body = messageSource.getMessage("push.analysis.unavailable.body", language),
                appLink = appLink,
            )

            else -> PushMessage(
                userId = pushResult.userId,
                type = PushType.ANALYSIS_FAILED,
                notificationId = notificationId,
                title = messageSource.getMessage("push.analysis.failed.title", language),
                body = messageSource.getMessage("push.analysis.failed.body", language),
                appLink = appLink,
            )
        }
    }
}

/**
 * 분석 시도 한 번이 어떻게 끝났는지. 지표의 outcome 태그 값이다.
 *
 * @property isFinal 작업이 끝났는지. 재시도·미룸은 작업이 큐에 남아 아직 끝나지 않았다
 */
private enum class AnalysisOutcome(val tagValue: String, val isFinal: Boolean) {
    COMPLETED("completed", true),

    // 인식 작업이 음식 줄을 저장하고 끝났다. 식사는 영양 작업을 기다린다.
    RECOGNIZED("recognized", true),
    RETRIED("retried", false),
    DELAYED("delayed", false),
    FAILED("failed", true),
}

/** 모델에 넘길 사진. 콘텐츠 타입이 있는 것만 넘긴다. */
private class AnalysisImage(val bytes: ByteArray, val contentType: String)
