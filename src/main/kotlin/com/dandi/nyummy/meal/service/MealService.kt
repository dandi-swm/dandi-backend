package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.image.enum.ImagePurpose
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.meal.calculator.calculateDailyNutritionEvaluation
import com.dandi.nyummy.meal.calculator.calculateMealNutrition
import com.dandi.nyummy.meal.calculator.calculateMonthlyCalendarRange
import com.dandi.nyummy.meal.calculator.calculateRecommendedDailyIntake
import com.dandi.nyummy.meal.calculator.pickRewardBox
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.CreateMealResponse
import com.dandi.nyummy.meal.dto.CreateMealV2Response
import com.dandi.nyummy.meal.dto.DailyMealV2Response
import com.dandi.nyummy.meal.dto.DailyMealsResponse
import com.dandi.nyummy.meal.dto.DailyMealsV2Response
import com.dandi.nyummy.meal.dto.DailyNutritionResponse
import com.dandi.nyummy.meal.dto.MealFoodResponse
import com.dandi.nyummy.meal.dto.MealResponse
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.dto.MealV2Response
import com.dandi.nyummy.meal.dto.MonthlyMealDayResponse
import com.dandi.nyummy.meal.dto.MonthlyMealsResponse
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.TodayMealSummaryResponse
import com.dandi.nyummy.meal.dto.UpdateMealFoodsRequest
import com.dandi.nyummy.meal.dto.UpdateMealNameRequest
import com.dandi.nyummy.meal.dto.UpdateMealNutritionRequest
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealAnalysisJob
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealAnalysisVersion
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.mapper.toCreateMealResponse
import com.dandi.nyummy.meal.mapper.toCreateMealV2Response
import com.dandi.nyummy.meal.mapper.toDailyMealResponse
import com.dandi.nyummy.meal.mapper.toDailyMealV2Response
import com.dandi.nyummy.meal.mapper.toEntity
import com.dandi.nyummy.meal.mapper.toMealResponse
import com.dandi.nyummy.meal.mapper.toMealStatusResponse
import com.dandi.nyummy.meal.mapper.toMealV2Response
import com.dandi.nyummy.meal.mapper.toNutrition
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.user.repository.ProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.random.Random

@Service
class MealService(
    private val mealRepository: MealRepository,
    private val mealAnalysisJobRepository: MealAnalysisJobRepository,
    private val imageService: ImageService,
    private val mealAttemptService: MealAttemptService,
    private val clock: Clock,
    private val profileRepository: ProfileRepository,
    private val mealProperties: MealProperties,
    private val analysisFeedbackService: AnalysisFeedbackService,
    private val mealTransactionService: MealTransactionService,
    private val mealRecognitionService: MealRecognitionService,
    private val mealFoodService: MealFoodService,
    private val random: Random,
    private val mailService: MailService,
) {

    companion object {
        private val logger = LoggerFactory.getLogger(MealService::class.java)

        // meals.image_key의 유니크 제약. 위반 메시지에 이 이름이 들어 있으면 같은 imageKey가 겹친 것이다.
        private const val MEAL_IMAGE_KEY_UNIQUE_CONSTRAINT = "uk_meals_image_key"
    }

    /**
     * 업로드된 이미지를 확정하고 식사 기록과 영양 분석 작업을 생성한다.
     *
     * 이미지는 [ImageService.createUploadUrl]로 발급받은 키에 이미 업로드되어 있어야 한다.
     * 별도 경로로 복사하지 않고 상태 태그만 `status=committed`로 바꾸므로,
     * 저장되는 [Meal.imageKey]는 요청으로 받은 키와 동일하다.
     *
     * 하나의 imageKey로 식사를 중복 생성할 수 없다. 소프트 삭제된 식사도 검사 대상에 포함되므로,
     * 한 번 사용된 imageKey는 다시 사용할 수 없다.
     *
     * 분석은 Worker가 작업을 가져가 따로 실행하므로, 반환되는 상태는 WAITING이다.
     * 결과는 [getMealStatus]로 확인하고, 끝나면 알림으로도 알린다.
     *
     * [Meal.mealAt]에는 이미지 EXIF에서 추출한 촬영 시각이 저장되고, EXIF가 없으면 서버 시각이
     * 들어간다. [MealAttemptService.validateCapturedAt]가 촬영 날짜를 오늘로 강제하므로 저장되는
     * 값은 어느 쪽이든 오늘 안의 시각이다.
     *
     * EXIF에서 온 값은 촬영 기기의 시계에 의존하므로 서버 시각과 최대 하루만큼 어긋날 수 있다.
     *
     * 기록 보상으로 보상 상자를 하나 뽑아 우편함에 보낸다. 식사와 우편은 [MealTransactionService]가 한 트랜잭션으로
     * 저장하므로, 우편 저장이 실패하면 식사 기록도 실패한다. 응답에는 뽑은 상자의 등급만 담는다(코인 수는 상자를 열 때 보여준다).
     *
     * @param userId 식사를 등록하는 사용자 ID
     * @param request 식사 생성 정보를 담은 [CreateMealRequest] (이미지 키)
     * @return 생성된 [Meal]의 분석 상태와 보상 상자 등급을 담은 [CreateMealResponse]
     * @throws BusinessException [MealErrorCode.DUPLICATE_IMAGE_KEY] 이미 식사 기록에 사용된 imageKey인 경우
     * @throws BusinessException [S3ErrorCode.INVALID_KEY] request.imageKey가 요청자 소유 경로(`meals/{userId}/`)가 아닌 경우
     * @throws BusinessException [S3ErrorCode.OBJECT_NOT_FOUND] request.imageKey에 해당하는 객체가 S3에 없는 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] 실제 업로드된 크기가 0이거나 [ImagePurpose.MEAL]의 최대 크기를 초과하는 경우
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] 실제 콘텐츠에서 감지된 MIME 타입이 허용되지 않거나, imageKey의 확장자와 다른 경우
     * @throws BusinessException [MealErrorCode.STALE_IMAGE] 오늘 촬영한 사진이 아닌 경우
     */
    fun createMeal(userId: Long, request: CreateMealRequest, language: Language): CreateMealResponse {
        if (mealRepository.existsByImageKey(request.imageKey)) {
            throw BusinessException(MealErrorCode.DUPLICATE_IMAGE_KEY)
        }

        // 업로드 URL 발급 때 이미 걸렀지만 여기가 유일한 DB 쓰기 지점이라 다시 검사한다.
        // 발급받은 URL을 들고 늦게 확정하거나, 업로드 URL 없이 바로 호출하는 경우를 막는다.
        //
        // 이미지 확정(confirmUpload) 앞에서 검사한다. 뒤에서 하면 거부해도 객체가 이미
        // status=committed라 라이프사이클 정리 대상에서 빠져 고아 객체로 남는다.
        //
        // 여기는 대부분의 초과 요청을 이미지 확정 전에 거르는 빠른 거절이다. 같은 사용자의 요청이 동시에
        // 이 검사를 함께 통과하는 경우까지 막는 정확한 제한은 [MealTransactionService.createMeal]이 맡는다.
        mealAttemptService.validateDailyCount(userId)

        // 오늘 촬영 검증은 콜백으로 넘겨 확정(status=committed) 전에 실행되게 한다.
        val uploadedImage = imageService.confirmUpload(
            userId = userId,
            purpose = ImagePurpose.MEAL,
            imageKey = request.imageKey,
            validateAfterUpload = { mealAttemptService.validateCapturedAt(userId, it) },
        )

        // EXIF가 없으면 거부하지 않고 서버 시각으로 채운다. 기기·앱에 따라 EXIF가 아예 없거나
        // 메신저를 거치며 제거되는데, 그 사용자의 기록 자체를 막는 건 과하다.
        val mealAt = uploadedImage.capturedAt ?: Instant.now(clock).also {
            logger.info("EXIF 촬영 시각 없음, 서버 시각으로 대체: userId={}, imageKey={}", userId, uploadedImage.imageKey)
        }

        val meal = request.toEntity(userId, mealAt, uploadedImage.imageKey)
        val rewardBox = pickRewardBox(mealProperties.rewardBoxes, random)
        val rewardMailExpiresAt = Instant.now(clock).plus(mealProperties.rewardMailTimeToLive)

        mealTransactionService.createMeal(meal, rewardBox, rewardMailExpiresAt, language)

        return meal.toCreateMealResponse(rewardBox.category)
    }

    /**
     * v2 식사 기록. 업로드된 이미지를 확정하고, 응답 기한 안에서 음식을 인식해 식사를 저장한다.
     *
     * 중복 검사·하루 횟수 검사·이미지 확정·촬영 시각 처리·보상 상자는 [createMeal]과 같다. 다른 점은 응답 전에 음식을
     * 인식한다는 것이다. 이미지 확정 때 내려받은 바이트를 그대로 인식에 쓰고, 응답 기한은 이 메서드에 들어온 때부터 센다.
     *
     * 인식을 시도한 결과에 따라 셋 중 하나로 응답한다.
     * - 음식으로 인식하면 WAITING이고, 대표 이름과 음식 줄을 함께 준다. 영양은 Worker가 조금 뒤에 계산한다.
     * - 음식이 아니면 FAILED이고 이름은 빈 문자열이다.
     * - 응답 기한 안에 인식을 끝내지 못했으면 RECOGNIZING이고, Worker가 이어서 인식한다. 이름과 음식 줄은 없다.
     *
     * 하루 기록 횟수, 보상 상자, 연속 기록은 인식 결과와 상관없이 기록한 것으로 센다.
     *
     * @param userId 식사를 등록하는 사용자 ID
     * @param request 식사 생성 정보를 담은 [CreateMealRequest] (이미지 키)
     * @param language 식사를 기록한 요청의 앱 언어. 대표 이름과 음식 이름을 이 언어로 받는다
     * @return 식사 ID, 상태, 보상 상자 등급, 인식한 대표 이름과 음식 줄을 담은 [CreateMealV2Response]
     * @throws BusinessException [MealErrorCode.DUPLICATE_IMAGE_KEY] 이미 식사 기록에 사용된 imageKey인 경우.
     *   같은 imageKey의 요청이 동시에 와서 저장할 때 겹친 경우도 같다
     * @throws BusinessException [MealErrorCode.DAILY_COUNT_EXCEEDED] 오늘 시도 횟수가 상한인 경우
     * @throws BusinessException [MealErrorCode.STALE_IMAGE] 오늘 촬영한 사진이 아닌 경우
     * @throws BusinessException 이미지 확정이 거부된 경우. [S3ErrorCode]는 [createMeal]과 같다
     */
    fun createMealV2(userId: Long, request: CreateMealRequest, language: Language): CreateMealV2Response {
        val receivedAt = Instant.now(clock)

        if (mealRepository.existsByImageKey(request.imageKey)) {
            throw BusinessException(MealErrorCode.DUPLICATE_IMAGE_KEY)
        }
        // 이미지 확정 앞에서 거르는 이유는 [createMeal]과 같다.
        mealAttemptService.validateDailyCount(userId)

        val uploadedImageContent = imageService.confirmUploadContent(
            userId = userId,
            purpose = ImagePurpose.MEAL,
            imageKey = request.imageKey,
            validateAfterUpload = { mealAttemptService.validateCapturedAt(userId, it) },
        )
        val uploadedImage = uploadedImageContent.uploadedImage
        val mealAt = uploadedImage.capturedAt ?: Instant.now(clock).also {
            logger.info("EXIF 촬영 시각 없음, 서버 시각으로 대체: userId={}, imageKey={}", userId, uploadedImage.imageKey)
        }

        val outcome = mealRecognitionService.recognizeMeal(
            imageBytes = uploadedImageContent.bytes,
            contentType = uploadedImageContent.contentType,
            language = language,
            receivedAt = receivedAt,
        )

        val meal = request.toEntity(userId, mealAt, uploadedImage.imageKey, outcome)
        val rewardBox = pickRewardBox(mealProperties.rewardBoxes, random)
        val rewardMailExpiresAt = Instant.now(clock).plus(mealProperties.rewardMailTimeToLive)

        val mealFoods = try {
            mealTransactionService.createMealV2(
                meal = meal,
                outcome = outcome,
                rewardBox = rewardBox,
                rewardMailExpiresAt = rewardMailExpiresAt,
                nutritionDelay = mealProperties.recognition.nutritionDelay,
                language = language,
            )
        } catch (e: DataIntegrityViolationException) {
            // 같은 imageKey의 요청이 동시에 오면 위의 중복 검사를 함께 통과하고, 늦은 쪽이 유니크 제약에 걸린다.
            if (e.mostSpecificCause.message?.contains(MEAL_IMAGE_KEY_UNIQUE_CONSTRAINT) != true) {
                throw e
            }
            logger.warn("같은 imageKey의 식사 기록이 동시에 들어와 유니크 제약과 충돌했습니다: userId={}", userId, e)
            throw BusinessException(MealErrorCode.DUPLICATE_IMAGE_KEY)
        }

        return meal.toCreateMealV2Response(rewardBox.category, mealFoods)
    }

    /**
     * v2 식사 단건을 조회한다. v1 응답에 분석 버전과 음식 줄을 더한다.
     *
     * 음식 줄은 목록 순서대로 주고, 줄 영양은 1인분 × 인분이다(아직 계산하지 않은 줄은 null).
     * 음식 줄이 없는 식사는 foods가 null이다. V1 식사, 아직 인식 중인 식사, 음식이 아닌 식사가 그렇다.
     *
     * @param userId 조회하는 사용자 ID
     * @param mealId 조회할 식사 ID
     * @return 식사 정보와 음식 줄, 이미지 URL, 평가 가능 여부를 담은 [MealV2Response]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional(readOnly = true)
    fun getMealV2(userId: Long, mealId: Long): MealV2Response {
        val meal = mealRepository.findMealByIdAndDeletedAtIsNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        return convertMealV2Response(meal)
    }

    /** 음식 줄을 읽어 v2 단건 응답으로 바꾼다. V1 식사는 음식 줄이 없으므로 조회하지 않는다. */
    private fun convertMealV2Response(meal: Meal): MealV2Response {
        val mealFoods = if (meal.analysisVersion == MealAnalysisVersion.V2) {
            mealFoodService.getMealFoods(meal.id)
        } else {
            emptyList()
        }

        return meal.toMealV2Response(
            imageUrl = imageService.createImageUrl(meal.imageKey),
            canCreateAnalysisFeedback = analysisFeedbackService.canCreateAnalysisFeedback(meal),
            mealFoods = mealFoods,
        )
    }

    /**
     * v2 하루 식사 목록을 조회한다. [getDailyMeals]와 같고, 식사마다 분석 버전을 더한다.
     * 앱은 이 값으로 상세 화면(음식 줄 화면, 기존 화면)을 고른다.
     *
     * @param userId 조회하는 사용자 ID
     * @param year 조회할 날짜의 연도
     * @param month 조회할 날짜의 월
     * @param day 조회할 날짜의 일
     * @return 해당 날짜의 식사 목록과 [DailyNutritionResponse]를 담은 [DailyMealsV2Response]
     */
    @Transactional(readOnly = true)
    fun getDailyMealsV2(userId: Long, year: Int, month: Int, day: Int): DailyMealsV2Response {
        val date = LocalDate.of(year, month, day)
        val meals = getMealsByDate(userId, date)

        return DailyMealsV2Response(
            date = date,
            meals = meals.map { it.toDailyMealV2Response() },
            dailyNutrition = createDailyNutritionResponse(userId, date, meals),
        )
    }

    /**
     * v2 식사의 음식 목록을 사용자가 보낸 목록으로 바꾸고 영양을 다시 계산하게 한다.
     * 음식 목록 화면의 버튼 하나가 이 API다. 고치지 않았어도 같은 목록을 보내고, 계산이 끝난 뒤에도 몇 번이든 보낼 수 있다.
     *
     * 기다리던 작업은 지운다. 옛 목록으로 계산하던 Worker는 결과를 쓸 때 자기 작업 행이 없어 결과를 버린다.
     * 그 뒤는 값을 새로 구해야 하는지에 따라 갈린다.
     * - 새로 구할 값이 있으면(이름을 바꾼 줄, 영양을 적지 않은 새 줄) 바로 가져갈 영양 작업을 만들고 WAITING으로 둔다.
     *   식사의 합계와 아이콘은 비운다. 계산이 끝나지 않은 식사는 영양이 없어야 하루 목록과 합계에 옛 값이 섞이지 않는다.
     *   아직 한 번도 계산이 끝나지 않은 식사도 여기로 간다. 고양이 코멘트를 만들어야 해서다.
     * - 새로 구할 값이 없으면(인분·순서만 바꿈, 줄을 지움, 영양·아이콘을 직접 적음) 이 요청 안에서 합계와 아이콘을 다시 계산해
     *   COMPLETED로 끝낸다. 작업을 만들지 않으므로 완료 푸시도 가지 않는다.
     *
     * 요청 안에서 끝내는 것은 식사 DB만으로 끝낼 수 있을 때뿐이다. 값을 새로 구하는 일(모델 호출, 음식 DB 조회)은 Worker가 한다.
     *
     * 대표 이름과 고양이 코멘트는 바꾸지 않는다.
     *
     * 음식 목록이 있는 V2 식사만 받는다. 아직 인식 중인 식사, 실패한 식사, V1 식사는 거절한다.
     * 실패한 식사는 재분석으로 처음부터 다시 한다.
     *
     * 줄 수·이름 길이·인분 범위·영양 범위는 요청 DTO가 검사하고, 음식 줄을 바꾸는 규칙은 [MealFoodService.updateMealFoods]에 있다.
     *
     * @param userId 보내는 사용자 ID
     * @param mealId 음식 목록을 바꿀 식사 ID
     * @param request 음식 줄 전체를 담은 [UpdateMealFoodsRequest]
     * @param language 요청의 앱 언어. 새 작업에 실어 완료 푸시를 이 언어로 보낸다
     * @return 다시 계산을 기다리면 WAITING, 요청 안에서 계산을 끝냈으면 COMPLETED를 담은 [MealStatusResponse]
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 인분이 0.5 단위가 아니거나, 없는 아이콘 id이거나, 같은 mealFoodId가 두 번 온 경우
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     * @throws BusinessException [MealErrorCode.MEAL_FOODS_NOT_UPDATABLE] 음식 목록을 고칠 수 없는 식사인 경우
     * @throws BusinessException [MealErrorCode.MEAL_FOOD_NOT_FOUND] 이 식사의 줄이 아닌 mealFoodId가 있는 경우
     */
    @Transactional
    fun updateMealFoodsV2(
        userId: Long,
        mealId: Long,
        request: UpdateMealFoodsRequest,
        language: Language,
    ): MealStatusResponse {
        if (request.foods.any { it.portion.remainder(MealFood.PORTION_STEP).signum() != 0 }) {
            throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE)
        }

        val meal = deleteMealAnalysisJobAndGetMealWithLock(userId, mealId)
        if (meal.analysisVersion != MealAnalysisVersion.V2 ||
            meal.status !in MealStatus.MEAL_FOOD_UPDATABLE_STATUSES
        ) {
            throw BusinessException(MealErrorCode.MEAL_FOODS_NOT_UPDATABLE)
        }

        val mealFoods = mealFoodService.updateMealFoods(meal.id, request.foods)
        val topMealFoodIconId = mealFoods.firstOrNull()?.iconId
        val hasAllServingNutritions = mealFoods.all { it.servingNutrition != null && it.iconId != null }
        if (meal.catComment != null && topMealFoodIconId != null && hasAllServingNutritions) {
            meal.updateNutrition(calculateMealNutrition(mealFoods))
            meal.updateIconId(topMealFoodIconId)
            meal.updateStatus(MealStatus.COMPLETED)
        } else {
            mealAnalysisJobRepository.save(
                MealAnalysisJob(mealId = meal.id, language = language, category = MealAnalysisJobCategory.NUTRITION),
            )
            meal.resetNutrition()
            meal.updateStatus(MealStatus.WAITING)
        }

        return meal.toMealStatusResponse()
    }

    /**
     * 식사의 기다리던 작업을 지우고 식사를 잠가 돌려준다. 작업 행을 만들거나 지우는 v2 요청이 이 순서로 시작한다.
     *
     * 1. 프로필 행을 잠가 같은 사용자의 요청을 줄 세운다([MealTransactionService.createMeal]과 같은 방식).
     *    줄 세우지 않으면 같은 식사의 요청 둘이 모두 "작업 없음"을 보고 새 작업을 둘 만들려다 유니크 제약에 걸린다.
     *    트랜잭션의 첫 조회여야 한다. 잠금보다 먼저 일반 조회를 하면 앞선 요청이 커밋한 작업을 보지 못한다.
     * 2. 작업 행이 있으면 id로 지운다. Worker와 같은 순서(작업 → 식사)로 잠가야 서로를 기다리는 데드락이 없다.
     *    `meal_id`로 바로 지우지 않는 이유는 [MealAnalysisJobRepository.findIdByMealId]에 있다.
     * 3. 식사를 잠가 읽는다.
     *
     * 식사의 소유자는 식사를 읽은 뒤에야 알 수 있어 작업을 먼저 지운다. 소유자가 아니면 예외로 트랜잭션이 롤백돼 작업도 되돌아온다.
     *
     * @throws BusinessException [UserErrorCode.PROFILE_NOT_FOUND] 가입 시 만들어져야 할 프로필이 없는 경우
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    private fun deleteMealAnalysisJobAndGetMealWithLock(userId: Long, mealId: Long): Meal {
        profileRepository.findProfileWithLockByUserId(userId)
            ?: throw BusinessException(UserErrorCode.PROFILE_NOT_FOUND)
        mealAnalysisJobRepository.findIdByMealId(mealId)
            ?.let { mealAnalysisJobRepository.deleteMealAnalysisJobById(it) }

        val meal = mealRepository.findMealWithLockById(mealId)
            ?.takeIf { it.deletedAt == null }
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        return meal
    }

    /**
     * v2 식사의 대표 이름을 수정한다. AI가 추천한 이름을 사용자가 바꿀 때 쓴다.
     *
     * 이름만 바꾼다. 음식 줄과 영양은 그대로이고 다시 계산하지 않는다.
     * 이름 길이(1~[Meal.NAME_MAX_LENGTH]자)는 요청 DTO가 검사한다.
     *
     * [updateMeal]과 같은 이유로 식사를 잠가 읽는다.
     *
     * @param userId 수정하는 사용자 ID
     * @param mealId 수정할 식사 ID
     * @param request 바꿀 대표 이름을 담은 [UpdateMealNameRequest]
     * @return 수정된 식사 정보와 음식 줄을 담은 [MealV2Response]. 단건 조회와 같은 모양이다
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional
    fun updateMealNameV2(userId: Long, mealId: Long, request: UpdateMealNameRequest): MealV2Response {
        val meal = mealRepository.findMealWithLockById(mealId)
            ?.takeIf { it.deletedAt == null }
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        meal.updateName(request.name)

        return convertMealV2Response(meal)
    }

    /**
     * v2 식사 기록을 소프트 삭제하고(deletedAt 기록) 기다리던 작업을 지운다.
     *
     * v1 삭제([deleteMeal])는 작업을 그대로 두고 Worker가 가져갈 때 지워진 식사의 작업을 지운다.
     * v2는 영양 작업이 5분을 기다리므로 여기서 바로 지운다. 그 작업을 처리하던 Worker는 결과를 쓸 때 자기 행이 없어 결과를 버린다.
     *
     * 음식 줄과 스냅샷은 지우지 않는다. 하루 기록 횟수, 보상 상자, 연속 기록도 되돌리지 않는다.
     *
     * @param userId 삭제하는 사용자 ID
     * @param mealId 삭제할 식사 ID
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional
    fun deleteMealV2(userId: Long, mealId: Long) {
        val meal = deleteMealAnalysisJobAndGetMealWithLock(userId, mealId)

        meal.updateDeletedAt(Instant.now(clock))
    }

    /**
     * v2 식사 기록을 취소한다. 기록 직후 음식 목록 화면의 X 버튼이다.
     *
     * 지금은 삭제([deleteMealV2])와 하는 일이 같다. 나중에 취소에만 다른 동작을 넣을 수 있게 API와 메서드를 따로 둔다.
     *
     * @param userId 취소하는 사용자 ID
     * @param mealId 취소할 식사 ID
     * @throws BusinessException [deleteMealV2]와 같다
     */
    @Transactional
    fun cancelMealV2(userId: Long, mealId: Long) {
        deleteMealV2(userId, mealId)
    }

    /**
     * 튜토리얼 식사를 기록하고 보상 상자 우편을 하나 만든다. 사용자당 한 번만 성공한다.
     *
     * 식사 기록(meals 행)은 만들지 않는다. 튜토리얼은 기록하는 흐름을 보여주기 위한 가짜 기록이라
     * 기록 조회·연속 기록·오늘 식사 현황·하루 시도 횟수에 들어가면 안 된다. 행을 만들고 조회마다 빼면
     * 앞으로 생길 조회도 매번 빼야 하므로, 프로필에 완료 시각만 남긴다.
     *
     * 보상은 골드 상자이고 만료되지 않는다. 상자에 든 아이템·코인은 설정(app.meal.tutorial-reward-box)에서 읽는다.
     * 가입 때 배경만 지급하므로, 나머지 기본 아이템은 이 상자를 받을 때 생긴다.
     *
     * 같은 사용자의 요청이 동시에 와도 상자가 두 번 생기지 않도록 프로필 행을 잠근 뒤 완료 여부를 본다.
     * 뒤에 온 요청은 앞 요청이 커밋할 때까지 기다렸다가 이미 완료한 것으로 거절된다.
     * 잠금보다 먼저 프로필을 일반 조회하면 REPEATABLE READ 스냅샷 때문에 앞 요청의 완료를 못 보므로 잠금 조회가 첫 조회다.
     *
     * @param userId 튜토리얼을 하는 사용자 ID
     * @param language 요청의 앱 언어. 보상 상자 우편의 문구를 이 언어로 저장한다
     * @throws BusinessException [UserErrorCode.PROFILE_NOT_FOUND] 가입 시 만들어져야 할 프로필이 없는 경우
     * @throws BusinessException [MealErrorCode.TUTORIAL_ALREADY_COMPLETED] 이미 튜토리얼을 완료한 경우
     */
    @Transactional
    fun createTutorialMeal(userId: Long, language: Language) {
        val profile = profileRepository.findProfileWithLockByUserId(userId)
            ?: throw BusinessException(UserErrorCode.PROFILE_NOT_FOUND)

        if (profile.isTutorialCompleted) {
            throw BusinessException(MealErrorCode.TUTORIAL_ALREADY_COMPLETED)
        }

        profile.updateTutorialCompletedAt(Instant.now(clock))
        mailService.createTutorialRewardMail(
            userId = userId,
            rewardBoxCategory = RewardBoxCategory.GOLD,
            coinQuantity = mealProperties.tutorialRewardBox.coinQuantity,
            expiresAt = null,
            itemIds = mealProperties.tutorialRewardBox.itemIds,
            language = language,
        )
    }

    /**
     * 특정 날짜의 식사 목록과 하루 영양 섭취 현황(현재/목표)을 조회한다.
     *
     * @param userId 조회하는 사용자 ID
     * @param year 조회할 날짜의 연도
     * @param month 조회할 날짜의 월
     * @param day 조회할 날짜의 일
     * @return 해당 날짜의 식사 목록과 [DailyNutritionResponse]를 담은 [DailyMealsResponse]
     */
    @Transactional(readOnly = true)
    fun getDailyMeals(userId: Long, year: Int, month: Int, day: Int): DailyMealsResponse {
        val date = LocalDate.of(year, month, day)
        val meals = getMealsByDate(userId, date)

        return DailyMealsResponse(
            date = date,
            meals = meals.map { it.toDailyMealResponse() },
            dailyNutrition = createDailyNutritionResponse(userId, date, meals),
        )
    }

    /** [date] 하루([0시, 다음 날 0시))에 먹은, 지우지 않은 식사. */
    private fun getMealsByDate(userId: Long, date: LocalDate): List<Meal> {
        // TODO: 사용자별 timezone에 맞게 계산
        val zone = ZoneId.of("Asia/Seoul")
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()

        return mealRepository.findMealsByUserIdAndPeriod(userId, start, end)
    }

    /** 하루 섭취 합계와 목표. 합계는 모든 식사를 더하고, 영양을 아직 모르는 식사는 0으로 더해진다. */
    private fun createDailyNutritionResponse(userId: Long, date: LocalDate, meals: List<Meal>): DailyNutritionResponse {
        val profile = profileRepository.findProfileByUserId(userId)

        return DailyNutritionResponse(
            current = meals.fold(Nutrition.ZERO) { acc, meal -> acc + meal.toNutrition() },
            target = calculateRecommendedDailyIntake(profile, date),
        )
    }

    /**
     * 월간 캘린더 범위([calculateMonthlyCalendarRange])의 날짜별 하루 평가를 계산해 조회한다.
     *
     * @param userId 조회하는 사용자 ID
     * @param year 조회할 연도
     * @param month 조회할 월
     * @return 캘린더 범위의 날짜별 [MonthlyMealDayResponse] 목록을 담은 [MonthlyMealsResponse]
     */
    @Transactional(readOnly = true)
    fun getMonthlyMeals(userId: Long, year: Int, month: Int): MonthlyMealsResponse {
        val zone = ZoneId.of("Asia/Seoul")
        val (startDate, endDate) = calculateMonthlyCalendarRange(YearMonth.of(year, month))

        val mealsByPeriod = mealRepository.findMealsByUserIdAndPeriod(
            userId,
            startDate.atStartOfDay(zone).toInstant(),
            endDate.plusDays(1).atStartOfDay(zone).toInstant(),
        )

        val mealsByDate: Map<LocalDate, List<Meal>> =
            mealsByPeriod
                .groupBy { it.mealAt.atZone(zone).toLocalDate() }

        val profile = profileRepository.findProfileByUserId(userId)
        val recommended = calculateRecommendedDailyIntake(profile, LocalDate.now())

        val days = mutableListOf<MonthlyMealDayResponse>()
        var date = startDate
        while (date <= endDate) {
            days.add(
                MonthlyMealDayResponse(
                    date = date,
                    isCurrentMonth = date.year == year && date.monthValue == month,
                    dailyNutritionEvaluation = calculateDailyNutritionEvaluation(
                        meals = mealsByDate[date] ?: emptyList(),
                        recommended = recommended,
                        intakeTolerance = mealProperties.intakeTolerance,
                        minPositiveMacroCount = mealProperties.minPositiveMacroCount,
                    ),
                    foodIconIds = pickFoodIconIds(mealsByDate[date] ?: emptyList()),
                ),
            )
            date = date.plusDays(1)
        }

        return MonthlyMealsResponse(
            year = year,
            month = month,
            days = days,
        )
    }

    private fun pickFoodIconIds(meals: List<Meal>): List<Long> {
        // 분석이 끝나지 않았거나 실패한 식사는 아이콘이 정해지지 않았으므로 후보에서 뺀다.
        // 캘린더 응답에는 status가 없어 클라이언트가 걸러낼 수 없다.
        val iconIdsByMealAt = meals
            .filter { it.status == MealStatus.COMPLETED }
            .sortedBy { it.mealAt }
            .mapNotNull { it.iconId }
        if (iconIdsByMealAt.size <= 2) {
            return iconIdsByMealAt
        }

        val distinctIconIds = iconIdsByMealAt.distinct()

        if (distinctIconIds.size < 2) {
            return iconIdsByMealAt.take(2)
        }

        val pickedIconIds = distinctIconIds.shuffled().take(2).toSet()
        return distinctIconIds.filter { it in pickedIconIds }
    }

    /**
     * 식사 단건을 조회하고 이미지 presigned URL을 발급해 반환한다.
     *
     * @param userId 조회하는 사용자 ID
     * @param mealId 조회할 식사 ID
     * @return 식사 정보와 이미지 URL, 평가 가능 여부를 담은 [MealResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional(readOnly = true)
    fun getMeal(userId: Long, mealId: Long): MealResponse {
        val meal = mealRepository.findMealByIdAndDeletedAtIsNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        val imageUrl = imageService.createImageUrl(meal.imageKey)
        val canCreateAnalysisFeedback = analysisFeedbackService.canCreateAnalysisFeedback(meal)

        return meal.toMealResponse(imageUrl, canCreateAnalysisFeedback)
    }

    /**
     * 식사의 영양 분석 상태를 조회한다.
     *
     * @param userId 조회를 요청한 사용자 ID (소유권 검증에 사용)
     * @param mealId 조회할 [Meal]의 ID
     * @return 분석 상태를 담은 [MealStatusResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] userId가 소유자가 아닌 경우
     */
    @Transactional(readOnly = true)
    fun getMealStatus(userId: Long, mealId: Long): MealStatusResponse {
        val meal = mealRepository.findMealByIdAndDeletedAtIsNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND, "Meal Not Found")

        meal.validateOwnership(userId)

        return meal.toMealStatusResponse()
    }

    /**
     * FAILED 상태의 식사에 대해 영양 분석을 다시 요청한다. 작업을 다시 쌓고 식사를 WAITING으로 둔다.
     *
     * 식사를 잠가 확인하므로, 같은 식사의 재시도가 동시에 들어와도 하나만 작업을 쌓고 나머지는
     * WAITING을 보고 [MealErrorCode.ANALYSIS_NOT_RETRYABLE]로 거부된다.
     *
     * @param userId 재시도를 요청한 사용자 ID (소유권 검증에 사용)
     * @param mealId 재시도할 [Meal]의 ID
     * @return 다시 요청한 뒤의 분석 상태(WAITING)를 담은 [MealStatusResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] userId가 소유자가 아닌 경우
     * @param language 재시도 요청의 앱 언어. 새 분석 결과와 완료 푸시를 이 언어로 만든다
     * @throws BusinessException [MealErrorCode.ANALYSIS_NOT_RETRYABLE] 식사가 FAILED 상태가 아닌 경우
     */
    @Transactional
    fun retryNutritionAnalysis(userId: Long, mealId: Long, language: Language): MealStatusResponse {
        val meal = mealRepository.findMealWithLockById(mealId)
            ?.takeIf { it.deletedAt == null }
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        if (meal.status != MealStatus.FAILED) {
            throw BusinessException(MealErrorCode.ANALYSIS_NOT_RETRYABLE)
        }

        mealAnalysisJobRepository.save(MealAnalysisJob(mealId = meal.id, language = language))
        meal.updateStatus(MealStatus.WAITING)

        return meal.toMealStatusResponse()
    }

    /**
     * FAILED 상태의 식사를 v2 경로로 다시 분석한다. V2 식사는 어느 단계에서 실패했든 처음부터, 음식 인식부터 다시 한다.
     *
     * V2 식사가 FAILED가 되는 경우는 셋이다: 음식이 아니라고 판정됨, 인식 실패, 영양 계산 실패. 앱에서는 모두 같은 실패로 보이므로
     * 재분석도 하나로 한다. 음식 줄과 스냅샷을 지우고(사용자가 고친 목록도 사라진다) 인식 작업을 만들어 RECOGNIZING으로 둔다.
     * 대표 이름과 합계·아이콘도 비운다. 고양이 코멘트가 이미 있으면 그대로 둔다(식사당 한 번만 만든다).
     *
     * V1 식사는 분석 버전을 바꿀 수 없으므로 [retryNutritionAnalysis]와 같이 v1 분석 작업을 쌓고 WAITING으로 둔다.
     * 새 앱의 하루 목록에는 V1 식사도 나오므로 이 경로로 들어올 수 있다.
     *
     * 작업 행을 만드는 요청이라 [deleteMealAnalysisJobAndGetMealWithLock]으로 시작한다. FAILED 식사에는 지울 작업이 없지만
     * 같은 사용자의 요청을 줄 세워, 재분석을 두 번 눌러도 하나만 작업을 만들고 나머지는 거절된다.
     *
     * 음식이 아니라고 잘못 판정한 식사를 세려고(재분석해서 성공한 식사) 다시 분석하는 식사의 id와 직전 입력 유형을 로그로 남긴다.
     *
     * @param userId 재분석을 요청한 사용자 ID
     * @param mealId 재분석할 식사 ID
     * @param language 요청의 앱 언어. 새 인식 결과와 푸시를 이 언어로 만든다
     * @return 다시 요청한 뒤의 분석 상태를 담은 [MealStatusResponse]. V2 식사는 RECOGNIZING, V1 식사는 WAITING이다
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] userId가 소유자가 아닌 경우
     * @throws BusinessException [MealErrorCode.ANALYSIS_NOT_RETRYABLE] 식사가 FAILED 상태가 아닌 경우
     */
    @Transactional
    fun retryNutritionAnalysisV2(userId: Long, mealId: Long, language: Language): MealStatusResponse {
        val meal = deleteMealAnalysisJobAndGetMealWithLock(userId, mealId)
        if (meal.status != MealStatus.FAILED) {
            throw BusinessException(MealErrorCode.ANALYSIS_NOT_RETRYABLE)
        }

        if (meal.analysisVersion != MealAnalysisVersion.V2) {
            mealAnalysisJobRepository.save(MealAnalysisJob(mealId = meal.id, language = language))
            meal.updateStatus(MealStatus.WAITING)

            return meal.toMealStatusResponse()
        }

        logger.info("실패한 v2 식사를 처음부터 다시 분석합니다: mealId={}, inputCategory={}", meal.id, meal.inputCategory)
        mealFoodService.deleteMealFoods(meal.id)
        mealAnalysisJobRepository.save(
            MealAnalysisJob(mealId = meal.id, language = language, category = MealAnalysisJobCategory.RECOGNITION),
        )
        meal.updateName("")
        meal.resetNutrition()
        meal.updateStatus(MealStatus.RECOGNIZING)

        return meal.toMealStatusResponse()
    }

    /**
     * 식사 이름을 수정한다.
     *
     * 식사를 잠가 읽는다. 엔티티 전체를 다시 쓰므로, 잠그지 않으면 읽은 뒤 Worker가 확정한 분석 결과를
     * 읽었던 값(ANALYZING·영양값 없음)으로 덮는다. 작업 행은 이미 지워져 그 식사는 분석 중에서 벗어나지 못한다.
     *
     * @param userId 수정하는 사용자 ID
     * @param mealId 수정할 식사 ID
     * @param name 변경할 식사 이름
     * @return 수정된 식사 정보와 이미지 URL, 평가 가능 여부를 담은 [MealResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional
    fun updateMeal(userId: Long, mealId: Long, name: String): MealResponse {
        val meal = mealRepository.findMealWithLockById(mealId)
            ?.takeIf { it.deletedAt == null }
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        val imageUrl = imageService.createImageUrl(meal.imageKey)

        meal.updateName(name)

        return meal.toMealResponse(imageUrl, analysisFeedbackService.canCreateAnalysisFeedback(meal))
    }

    /**
     * 식사의 영양(칼로리·탄수화물·단백질·지방)을 수정한다. 요청에 담긴 항목만 바꾸고 생략한 항목은 유지한다.
     *
     * 분석이 완료된(COMPLETED) 식사만 수정할 수 있다. 분석 전·분석 중인 식사는 Worker가 분석을 확정하면서
     * 영양을 덮어쓰고, 실패한 식사는 영양이 없는 채로 COMPLETED가 아니어서 집계에 들어가지 않는다.
     *
     * [updateMeal]과 같은 이유로 식사를 잠가 읽는다.
     *
     * 이름·아이콘·고양이 한마디(catComment)는 바꾸지 않는다. 고양이 한마디는 분석 때의 영양으로 쓴 문구라
     * 수정한 값과 어긋날 수 있다.
     *
     * @param userId 수정하는 사용자 ID
     * @param mealId 수정할 식사 ID
     * @param request 바꾸려는 영양 항목을 담은 [UpdateMealNutritionRequest]
     * @return 수정된 식사 정보와 이미지 URL, 평가 가능 여부를 담은 [MealResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     * @throws BusinessException [MealErrorCode.ANALYSIS_NOT_COMPLETED] 식사가 COMPLETED 상태가 아닌 경우
     */
    @Transactional
    fun updateMealNutrition(userId: Long, mealId: Long, request: UpdateMealNutritionRequest): MealResponse {
        val meal = mealRepository.findMealWithLockById(mealId)
            ?.takeIf { it.deletedAt == null }
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        if (meal.status != MealStatus.COMPLETED) {
            throw BusinessException(MealErrorCode.ANALYSIS_NOT_COMPLETED)
        }

        meal.updateNutrition(request.toNutrition(meal))

        val imageUrl = imageService.createImageUrl(meal.imageKey)
        val mealFeedback = analysisFeedbackService.canCreateAnalysisFeedback(meal)

        return meal.toMealResponse(imageUrl, mealFeedback)
    }

    /**
     * 식사 기록을 소프트 삭제한다(deletedAt 기록).
     *
     * [updateMeal]과 같은 이유로 식사를 잠가 읽는다.
     *
     * @param userId 삭제하는 사용자 ID
     * @param mealId 삭제할 식사 ID
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional
    fun deleteMeal(userId: Long, mealId: Long) {
        val meal = mealRepository.findMealWithLockById(mealId)
            ?.takeIf { it.deletedAt == null }
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        meal.validateOwnership(userId)

        meal.updateDeletedAt(Instant.now())
    }

    /**
     * 오늘의 식사 현황을 조회한다.
     *
     * 끼니 수는 분석 상태와 무관하게 세고, 섭취 칼로리는 분석이 끝난 식사만 더한다.
     * 분석 중인 식사도 사용자가 이미 기록한 끼니이므로 개수에서 빠지면 어색하지만,
     * 칼로리는 아직 값이 없어 더할 것이 없기 때문이다.
     *
     * 목표 칼로리는 프로필에서 계산하며, 프로필이 비어 있으면 기본 권장량이 쓰인다.
     *
     * @param userId 조회하는 사용자 ID
     * @return 오늘 기록한 끼니 수·기록 시도 횟수와 상한, 섭취·목표 칼로리를 담은 [TodayMealSummaryResponse]
     */
    @Transactional(readOnly = true)
    fun getTodayMealSummary(userId: Long): TodayMealSummaryResponse {
        // TODO: 사용자별 timezone에 맞게 계산
        val zone = ZoneId.of("Asia/Seoul")
        val today = Instant.now(clock).atZone(zone).toLocalDate()

        // 하루 경계는 [오늘 0시, 내일 0시)로 잡는다. 지금 시각까지만 조회하면
        // 기기 시계가 앞서 미래 시각으로 기록된 식사가 집계에서 빠진다.
        val start = today.atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()

        val meals = mealRepository.findMealsByUserIdAndPeriod(userId, start, end)

        // 분석 전·실패한 식사는 calory가 null이므로 집계에서 제외한다.
        val currentCalory = meals
            .filter { it.status == MealStatus.COMPLETED }
            .sumOf { it.calory ?: 0 }

        val profile = profileRepository.findProfileByUserId(userId)
        val targetCalory = calculateRecommendedDailyIntake(profile, today).calory

        return TodayMealSummaryResponse(
            todayRecordedCount = meals.size,
            todayAttemptCount = mealAttemptService.countDailyAttempts(userId, today).toInt(),
            todayMaxAttemptCount = mealProperties.maxDailyCount,
            todayCurrentCalory = currentCalory,
            todayTargetCalory = targetCalory,
        )
    }
}
