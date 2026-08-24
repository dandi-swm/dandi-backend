package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.infra.aws.s3.S3Service
import com.dandi.nyummy.meal.calculator.calculateDailyNutritionEvaluation
import com.dandi.nyummy.meal.calculator.calculateMonthlyCalendarRange
import com.dandi.nyummy.meal.calculator.calculateRecommendedDailyIntake
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.DailyMealsResponse
import com.dandi.nyummy.meal.dto.DailyNutritionResponse
import com.dandi.nyummy.meal.dto.GetStatusResponse
import com.dandi.nyummy.meal.dto.MealResponse
import com.dandi.nyummy.meal.dto.MonthlyMealDayResponse
import com.dandi.nyummy.meal.dto.MonthlyMealsResponse
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.UploadImageRequest
import com.dandi.nyummy.meal.dto.UploadImageResponse
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.mapper.toDailyMealResponse
import com.dandi.nyummy.meal.mapper.toEntity
import com.dandi.nyummy.meal.mapper.toMealResponse
import com.dandi.nyummy.meal.mapper.toNutrition
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.profile.repository.ProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@Service
class MealService(
    private val analysisService: AnalysisService,
    private val mealRepository: MealRepository,
    private val s3Service: S3Service,
    private val clock: Clock = Clock.System,
    private val profileRepository: ProfileRepository,
    private val mealProperties: MealProperties,
) {

    /**
     * 식사 이미지를 임시 경로에 업로드할 수 있는 presigned URL을 발급한다.
     *
     * @param request 업로드할 이미지의 [UploadImageRequest] (MIME 타입, 파일 크기)
     * @return 업로드 URL/메서드/헤더와 임시 이미지 키를 담은 [UploadImageResponse]
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] contentType이 허용되지 않는 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] fileSizeBytes가 [MealProperties.maxFileSizeBytes]를 초과하거나 음수인 경우
     */
    fun createUploadUrl(request: UploadImageRequest): UploadImageResponse {
        val expirationInstant = clock.now() + mealProperties.presignedUrlExpirationMinutes.minutes

        val uploadUrl = s3Service.createUploadUrl(
            contentType = request.contentType,
            fileSizeBytes = request.fileSizeBytes,
            maxFileSizeBytes = mealProperties.maxFileSizeBytes,
            expiration = mealProperties.presignedUrlExpirationMinutes.minutes,
        )

        return UploadImageResponse(
            uploadUrl = uploadUrl.url.toString(),
            imageKey = uploadUrl.key,
            uploadMethod = mealProperties.uploadMethod,
            uploadHeaders = mapOf("Content-Type" to request.contentType),
            expiresAt = expirationInstant.toString(),
        )
    }

    /**
     * 임시 업로드된 이미지를 확정하고 식사 기록을 생성한 뒤, 영양 분석을 비동기로 시작한다.
     *
     * @param userId 식사를 등록하는 사용자 ID
     * @param request 식사 생성 정보를 담은 [CreateMealRequest] (임시 이미지 키 등)
     * @return 생성된 [Meal]의 분석 상태를 담은 [GetStatusResponse]
     * @throws BusinessException [S3ErrorCode.INVALID_KEY] request.imageKey가 `temp/`로 시작하지 않는 경우
     * @throws BusinessException [S3ErrorCode.OBJECT_NOT_FOUND] request.imageKey에 해당하는 임시 객체가 S3에 없는 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] 실제 업로드된 크기가 0이거나 [MealProperties.maxFileSizeBytes]를 초과하는 경우
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] 실제 콘텐츠에서 감지된 MIME 타입이 허용되지 않는 경우
     */
    fun createMeal(userId: Long, request: CreateMealRequest): MealResponse {
        val imageKey = s3Service.confirmUpload(
            tempKey = request.imageKey,
            finalKeyPrefix = "meals",
            maxFileSizeBytes = mealProperties.maxFileSizeBytes,
        )

        val defaultIconId = 1

        val meal = request.toEntity(userId, imageKey)

        mealRepository.save(meal)

        val analyzedMeal = analysisService.analyzeNutrition(meal)

        val imageUrl = s3Service.createPresignedGetUrl(meal.imageKey, 10.minutes).toString()

        // TODO: AI에게 name과 icon 반환하게 하기
        analyzedMeal.name = "엽떡"
        analyzedMeal.iconId = 1

        return analyzedMeal.toMealResponse(imageUrl)
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
                    foodIconIds = emptyList(),
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

        val imageUrl = s3Service.createPresignedGetUrl(meal.imageKey, 10.minutes).toString()

        return meal.toMealResponse(imageUrl.toString())
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

        val imageUrl = s3Service.createPresignedGetUrl(meal.imageKey, 10.minutes).toString()

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
}
