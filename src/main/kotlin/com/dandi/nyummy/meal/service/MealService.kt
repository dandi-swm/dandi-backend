package com.dandi.nyummy.meal.service

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
import com.dandi.nyummy.meal.mapper.toGetStatusResponse
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
    @Transactional
    fun createMeal(userId: Long, request: CreateMealRequest): GetStatusResponse {
        val imageKey = s3Service.confirmUpload(
            tempKey = request.imageKey,
            finalKeyPrefix = "meals",
            maxFileSizeBytes = mealProperties.maxFileSizeBytes,
        )

        val meal = request.toEntity(userId, imageKey)
        val savedMeal = mealRepository.save(meal)

        analysisService.analyzeNutrition(userId, savedMeal.id)

        return savedMeal.toGetStatusResponse()
    }

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

    @Transactional(readOnly = true)
    fun getMeal(userId: Long, mealId: Long): MealResponse {
        val meal = mealRepository.getMealByIdAndUserIdAndDeletedAtIsNull(mealId, userId)
            ?: throw Exception("Meal Not Found")

        val imageUrl = s3Service.createPresignedGetUrl(meal.imageKey, 10.minutes).toString()

        return meal.toMealResponse(imageUrl)
    }

    @Transactional
    fun updateMeal(userId: Long, mealId: Long, name: String): MealResponse {
        val meal = mealRepository.getMealByIdAndUserIdAndDeletedAtIsNull(mealId, userId)
            ?: throw Exception("Meal Not Found")

        val imageUrl = s3Service.createPresignedGetUrl(meal.imageKey, 10.minutes).toString()

        meal.updateName(name)

        return meal.toMealResponse(imageUrl)
    }

    @Transactional
    fun deleteMeal(userId: Long, mealId: Long) {
        val meal = mealRepository.getMealByIdAndUserIdAndDeletedAtIsNull(mealId, userId)
            ?: throw Exception("Meal Not Found")

        meal.updateDeletedAt(Instant.now())
    }
}
