package com.dandi.nyummy.meal.facade

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.infra.aws.s3.S3Service
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.CreateMealRequest
import com.dandi.nyummy.meal.dto.DailyMealsResponse
import com.dandi.nyummy.meal.dto.MealResponse
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.dto.MonthlyMealsResponse
import com.dandi.nyummy.meal.dto.UploadImageRequest
import com.dandi.nyummy.meal.dto.UploadImageResponse
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.mapper.toEntity
import com.dandi.nyummy.meal.service.AnalysisService
import com.dandi.nyummy.meal.service.MealService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.minutes

/**
 * 식사 기능의 컨트롤러 진입점.
 *
 * 외부 I/O(S3)와 여러 Service 호출을 조율하며, 트랜잭션을 열지 않는다.
 * 트랜잭션은 [MealService], [AnalysisService]의 메서드 단위로 열린다.
 */
@Component
class MealFacade(
    private val mealService: MealService,
    private val analysisService: AnalysisService,
    private val s3Service: S3Service,
    private val mealProperties: MealProperties,
    private val clock: Clock,
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
     * 업로드된 이미지를 확정하고 식사 기록과 비동기 영양 분석 요청을 함께 저장한다.
     *
     * 이미지는 [createUploadUrl]로 발급받은 키에 이미 업로드되어 있어야 한다.
     * 별도 경로로 복사하지 않고 상태 태그만 `status=committed`로 바꾸므로,
     * 저장되는 [Meal.imageKey]는 요청으로 받은 키와 동일하다.
     *
     * 하나의 imageKey로 식사를 중복 생성할 수 없다. 소프트 삭제된 식사도 검사 대상에 포함되므로,
     * 한 번 사용된 imageKey는 다시 사용할 수 없다.
     *
     * 이미지 검증은 DB 트랜잭션 밖에서 실행한다. 식사와 Outbox 저장이 커밋되면
     * WAITING 상태를 반환하며, 실제 분석은 별도 Worker에서 수행한다.
     *
     * [Meal.mealAt]에는 서버 시각이 아니라 이미지 EXIF에서 추출한 촬영 시각이 저장된다.
     * [S3Service.confirmUploadedMealImage]가 촬영 시각과 현재 시각의 차이를
     * [MealProperties.captureTimeTolerance] 이내로 강제하므로, 방금 촬영한 사진만 등록할 수 있다.
     * 즉 저장되는 값은 촬영 기기의 시계에서 온 값이며, 서버 시각과 최대 허용 오차만큼 어긋날 수 있다.
     *
     * @param userId 식사를 등록하는 사용자 ID
     * @param request 식사 생성 정보를 담은 [CreateMealRequest] (이미지 키)
     * @return 생성된 [Meal]의 분석 상태를 담은 [MealStatusResponse]
     * @throws BusinessException [MealErrorCode.DUPLICATE_IMAGE_KEY] 이미 식사 기록에 사용된 imageKey인 경우
     * @throws BusinessException [S3ErrorCode.INVALID_KEY] request.imageKey가 요청자 소유 경로(`meals/{userId}/`)가 아닌 경우
     * @throws BusinessException [S3ErrorCode.OBJECT_NOT_FOUND] request.imageKey에 해당하는 객체가 S3에 없는 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] 실제 업로드된 크기가 0이거나 [MealProperties.maxFileSizeBytes]를 초과하는 경우
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] 실제 콘텐츠에서 감지된 MIME 타입이 허용되지 않거나, imageKey의 확장자와 다른 경우
     * @throws BusinessException [MealErrorCode.CAPTURE_TIME_NOT_FOUND] 이미지 EXIF에서 촬영 시각을 읽을 수 없는 경우
     * @throws BusinessException [MealErrorCode.STALE_IMAGE] 촬영 시각과 현재 시각의 차이가
     *   [MealProperties.captureTimeTolerance] 이상인 경우
     */
    fun createMeal(userId: Long, request: CreateMealRequest): MealStatusResponse {
        if (mealService.existsMealByImageKey(request.imageKey)) {
            throw BusinessException(MealErrorCode.DUPLICATE_IMAGE_KEY)
        }

        val (imageKey, capturedAt) = s3Service.confirmUploadedMealImage(
            userId = userId,
            imageKey = request.imageKey,
            maxFileSizeBytes = mealProperties.maxFileSizeBytes,
        )

        val meal = request.toEntity(userId, capturedAt, imageKey)

        return try {
            mealService.createMeal(meal)
        } catch (e: DataIntegrityViolationException) {
            if (mealService.existsMealByImageKey(request.imageKey)) {
                throw BusinessException(MealErrorCode.DUPLICATE_IMAGE_KEY)
            }
            throw e
        }
    }

    fun getDailyMeals(userId: Long, year: Int, month: Int, day: Int): DailyMealsResponse =
        mealService.getDailyMeals(userId, year, month, day)

    fun getMonthlyMeals(userId: Long, year: Int, month: Int): MonthlyMealsResponse =
        mealService.getMonthlyMeals(userId, year, month)

    /**
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    fun getMeal(userId: Long, mealId: Long): MealResponse = mealService.getMeal(userId, mealId)

    /**
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    fun updateMeal(userId: Long, mealId: Long, name: String): MealResponse =
        mealService.updateMeal(userId, mealId, name)

    /**
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    fun deleteMeal(userId: Long, mealId: Long) = mealService.deleteMeal(userId, mealId)

    /**
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, userId가 소유자가 아닌 경우
     */
    fun getNutritionAnalysisStatus(userId: Long, mealId: Long): MealStatusResponse =
        analysisService.getNutritionAnalysisStatus(userId, mealId)

    /**
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, userId가 소유자가 아닌 경우
     * @throws BusinessException [MealErrorCode.ANALYSIS_NOT_RETRYABLE] 식사가 FAILED 상태가 아닌 경우
     */
    fun retryNutritionAnalysis(userId: Long, mealId: Long): MealStatusResponse =
        analysisService.retryNutritionAnalysis(userId, mealId)
}
