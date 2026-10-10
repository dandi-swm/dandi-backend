package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.infra.image.s3.S3Service
import com.dandi.nyummy.meal.calculator.calculateDailyNutritionEvaluation
import com.dandi.nyummy.meal.calculator.calculateMonthlyCalendarRange
import com.dandi.nyummy.meal.calculator.calculateRecommendedDailyIntake
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.DailyMealsResponse
import com.dandi.nyummy.meal.dto.DailyNutritionResponse
import com.dandi.nyummy.meal.dto.MealResponse
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.dto.MonthlyMealDayResponse
import com.dandi.nyummy.meal.dto.MonthlyMealsResponse
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.Streak
import com.dandi.nyummy.meal.dto.TodayMealSummary
import com.dandi.nyummy.meal.dto.UploadImageRequest
import com.dandi.nyummy.meal.dto.UploadImageResponse
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.mapper.toDailyMealResponse
import com.dandi.nyummy.meal.mapper.toEntity
import com.dandi.nyummy.meal.mapper.toMealResponse
import com.dandi.nyummy.meal.mapper.toMealStatusResponse
import com.dandi.nyummy.meal.mapper.toNutrition
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.user.repository.ProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.minutes

@Service
class MealService(
    private val analysisService: AnalysisService,
    private val mealRepository: MealRepository,
    private val s3Service: S3Service,
    private val clock: Clock,
    private val profileRepository: ProfileRepository,
    private val mealProperties: MealProperties,
) {

    /**
     * 식사 이미지를 업로드할 수 있는 presigned URL을 발급한다.
     *
     * 발급된 객체 키는 `meals/{userId}/...` 형식으로 서버가 생성하며, 업로드 직후에는
     * `status=temp` 태그가 붙은 미확정 상태다. [createMeal]로 확정되지 않으면 S3
     * 라이프사이클 룰이 정리하므로 서버가 따로 삭제하지 않는다.
     *
     * @param userId 업로드를 요청한 사용자 ID
     * @param request 업로드할 이미지의 [UploadImageRequest] (MIME 타입, 파일 크기)
     * @return 업로드 URL/메서드/헤더와 이미지 키를 담은 [UploadImageResponse].
     *   [UploadImageResponse.uploadHeaders]는 업로드 요청에 그대로 포함해야 하며,
     *   누락하면 서명이 일치하지 않아 업로드가 거부된다
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] contentType이 허용되지 않는 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] fileSizeBytes가 [MealProperties.maxFileSizeBytes]를 초과하거나 음수인 경우
     */
    fun createUploadUrl(userId: Long, request: UploadImageRequest): UploadImageResponse {
        val expirationInstant = Instant.now(clock)
            .plus(mealProperties.presignedUrlExpirationMinutes.toLong(), ChronoUnit.MINUTES)

        val uploadUrl = s3Service.createMealUploadUrl(
            userId = userId,
            contentType = request.contentType,
            fileSizeBytes = request.fileSizeBytes,
            maxFileSizeBytes = mealProperties.maxFileSizeBytes,
            expiration = mealProperties.presignedUrlExpirationMinutes.minutes,
        )

        return UploadImageResponse(
            uploadUrl = uploadUrl.url,
            imageKey = uploadUrl.key,
            uploadMethod = mealProperties.uploadMethod,
            uploadHeaders = uploadUrl.uploadHeaders,
            expiresAt = expirationInstant.toString(),
        )
    }

    /**
     * 업로드된 이미지를 확정하고 식사 기록을 생성한 뒤, 영양 분석을 수행한다.
     *
     * 이미지는 [createUploadUrl]로 발급받은 키에 이미 업로드되어 있어야 한다.
     * 별도 경로로 복사하지 않고 상태 태그만 `status=committed`로 바꾸므로,
     * 저장되는 [Meal.imageKey]는 요청으로 받은 키와 동일하다.
     *
     * 하나의 imageKey로 식사를 중복 생성할 수 없다. 소프트 삭제된 식사도 검사 대상에 포함되므로,
     * 한 번 사용된 imageKey는 다시 사용할 수 없다.
     *
     * 분석은 [AnalysisService.analyzeNutrition]에서 동기로 실행되므로,
     * 반환되는 상태는 이미 COMPLETED 또는 FAILED로 확정된 값이다.
     *
     * [Meal.mealAt]에는 이미지 EXIF에서 추출한 촬영 시각이 저장되고, EXIF가 없으면 서버 시각이
     * 들어간다. [S3Service.confirmUploadedMealImage]가 촬영 날짜를 오늘로 강제하므로 저장되는
     * 값은 어느 쪽이든 오늘 안의 시각이다.
     *
     * EXIF에서 온 값은 촬영 기기의 시계에 의존하므로 서버 시각과 최대 하루만큼 어긋날 수 있다.
     *
     * @param userId 식사를 등록하는 사용자 ID
     * @param request 식사 생성 정보를 담은 [CreateMealRequest] (이미지 키)
     * @return 생성된 [Meal]의 분석 상태를 담은 [MealStatusResponse]
     * @throws BusinessException [MealErrorCode.DUPLICATE_IMAGE_KEY] 이미 식사 기록에 사용된 imageKey인 경우
     * @throws BusinessException [S3ErrorCode.INVALID_KEY] request.imageKey가 요청자 소유 경로(`meals/{userId}/`)가 아닌 경우
     * @throws BusinessException [S3ErrorCode.OBJECT_NOT_FOUND] request.imageKey에 해당하는 객체가 S3에 없는 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] 실제 업로드된 크기가 0이거나 [MealProperties.maxFileSizeBytes]를 초과하는 경우
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] 실제 콘텐츠에서 감지된 MIME 타입이 허용되지 않거나, imageKey의 확장자와 다른 경우
     * @throws BusinessException [MealErrorCode.STALE_IMAGE] 오늘 촬영한 사진이 아닌 경우
     */
    fun createMeal(userId: Long, request: CreateMealRequest): MealStatusResponse {
        if (mealRepository.existsByImageKey(request.imageKey)) {
            throw BusinessException(MealErrorCode.DUPLICATE_IMAGE_KEY)
        }

        val (imageKey, capturedAt) = s3Service.confirmUploadedMealImage(
            userId = userId,
            imageKey = request.imageKey,
            maxFileSizeBytes = mealProperties.maxFileSizeBytes,
        )

        val meal = request.toEntity(userId, capturedAt, imageKey)

        mealRepository.save(meal)

        analysisService.analyzeNutrition(meal)

        return meal.toMealStatusResponse()
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
        // TODO: 사용자별 timezone에 맞게 계산
        val zone = ZoneId.of("Asia/Seoul")
        val date = LocalDate.of(year, month, day)
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()

        val mealsByPeriod = mealRepository.getMealsByUserIdAndPeriod(userId, start, end)

        val meals = mealsByPeriod.map { it.toDailyMealResponse() }

        val profile = profileRepository.getProfileByUserId(userId)

        val recommended = calculateRecommendedDailyIntake(profile, LocalDate.of(year, month, day))

        val dailyNutrition = DailyNutritionResponse(
            current = mealsByPeriod.fold(Nutrition.ZERO) { acc, meal -> acc + meal.toNutrition() },
            target = recommended,
        )

        return DailyMealsResponse(
            date = LocalDate.of(year, month, day),
            meals = meals,
            dailyNutrition = dailyNutrition,
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

        val mealsByPeriod = mealRepository.getMealsByUserIdAndPeriod(
            userId,
            startDate.atStartOfDay(zone).toInstant(),
            endDate.plusDays(1).atStartOfDay(zone).toInstant(),
        )

        val mealsByDate: Map<LocalDate, List<Meal>> =
            mealsByPeriod
                .groupBy { it.mealAt.atZone(zone).toLocalDate() }

        val profile = profileRepository.getProfileByUserId(userId)
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
        val iconIdsByMealAt = meals.sortedBy { it.mealAt }.map { it.iconId }
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
     * @return 식사 정보와 이미지 URL을 담은 [MealResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional(readOnly = true)
    fun getMeal(userId: Long, mealId: Long): MealResponse {
        val meal = mealRepository.getMealByIdAndDeletedAtIsNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        if (meal.userId != userId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }

        val imageUrl = s3Service.createPresignedGetUrl(meal.imageKey, 10.minutes)

        return meal.toMealResponse(imageUrl)
    }

    /**
     * 식사 이름을 수정한다.
     *
     * @param userId 수정하는 사용자 ID
     * @param mealId 수정할 식사 ID
     * @param name 변경할 식사 이름
     * @return 수정된 식사 정보와 이미지 URL을 담은 [MealResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional
    fun updateMeal(userId: Long, mealId: Long, name: String): MealResponse {
        val meal = mealRepository.getMealByIdAndDeletedAtIsNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        if (meal.userId != userId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }

        val imageUrl = s3Service.createPresignedGetUrl(meal.imageKey, 10.minutes)

        meal.updateName(name)

        return meal.toMealResponse(imageUrl)
    }

    /**
     * 식사 기록을 소프트 삭제한다(deletedAt 기록).
     *
     * @param userId 삭제하는 사용자 ID
     * @param mealId 삭제할 식사 ID
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional
    fun deleteMeal(userId: Long, mealId: Long) {
        val meal = mealRepository.getMealByIdAndDeletedAtIsNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        if (meal.userId != userId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }

        meal.updateDeletedAt(Instant.now())
    }

    @Transactional(readOnly = true)
    fun getStreak(userId: Long): Streak {
        // TODO: 스트릭 구현하기
        return Streak(0, 0)
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
     * @return 오늘 기록한 끼니 수와 섭취·목표 칼로리를 담은 [TodayMealSummary]
     */
    @Transactional(readOnly = true)
    fun getTodayMealSummary(userId: Long): TodayMealSummary {
        // TODO: 사용자별 timezone에 맞게 계산
        val zone = ZoneId.of("Asia/Seoul")
        val today = Instant.now(clock).atZone(zone).toLocalDate()

        // 하루 경계는 [오늘 0시, 내일 0시)로 잡는다. 지금 시각까지만 조회하면
        // 기기 시계가 앞서 미래 시각으로 기록된 식사가 집계에서 빠진다.
        val start = today.atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()

        val meals = mealRepository.getMealsByUserIdAndPeriod(userId, start, end)

        // 분석 전·실패한 식사는 calory가 null이므로 집계에서 제외한다.
        val currentCalory = meals
            .filter { it.status == MealStatus.COMPLETED }
            .sumOf { it.calory ?: 0 }

        val profile = profileRepository.getProfileByUserId(userId)
        val targetCalory = calculateRecommendedDailyIntake(profile, today).calory

        return TodayMealSummary(
            todayRecordedCount = meals.size,
            todayCurrentCalory = currentCalory,
            todayTargetCalory = targetCalory,
        )
    }
}
