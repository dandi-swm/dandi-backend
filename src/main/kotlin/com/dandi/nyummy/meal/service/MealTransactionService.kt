package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.meal.dto.MealRecognitionOutcome
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.RewardBoxResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealAnalysisJob
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.entity.MealFoodSnapshot
import com.dandi.nyummy.meal.entity.MealSnapshot
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealRecognitionCategory
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.meal.repository.MealFoodRepository
import com.dandi.nyummy.meal.repository.MealFoodSnapshotRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.meal.repository.MealSnapshotRepository
import com.dandi.nyummy.streak.service.StreakService
import com.dandi.nyummy.user.repository.ProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 식사 도메인에서 외부 호출(S3·AI)과 분리한 짧은 트랜잭션을 모은다.
 * [MealService.createMeal]은 외부 호출 때문에 트랜잭션을 열지 않고,
 * DB에 쓰는 부분만 이 빈을 거쳐 커밋한다. 같은 클래스 안에서 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않으므로
 * 별도 빈으로 두고, 이 클래스의 메서드끼리도 서로 부르지 않는다.
 */
@Service
class MealTransactionService(
    private val profileRepository: ProfileRepository,
    private val mealAttemptService: MealAttemptService,
    private val mealRepository: MealRepository,
    private val mealAnalysisJobRepository: MealAnalysisJobRepository,
    private val mealSnapshotRepository: MealSnapshotRepository,
    private val mealFoodSnapshotRepository: MealFoodSnapshotRepository,
    private val mealFoodRepository: MealFoodRepository,
    private val mailService: MailService,
    private val streakService: StreakService,
) {
    /**
     * 식사와 보상 상자 우편, 영양 분석 작업을 한 트랜잭션으로 저장하고 연속 기록을 갱신한다. 우편·작업 저장이나 연속 기록 갱신이
     * 실패하면 식사도 남기지 않는다. 연속 기록은 분석 결과와 상관없이 식사를 만든 날을 인정하므로 여기서 갱신한다.
     *
     * 영양 분석은 Worker가 작업을 가져가 따로 처리한다. 식사와 작업을 같은 트랜잭션에 저장하므로, 식사만 남고 분석이 빠지는 일이 없다.
     *
     * 하루 시도 횟수는 [MealService.createMeal]이 이미지 확정 전에 한 번 거르지만, 그 검사와 저장 사이에
     * 같은 사용자의 요청이 동시에 들어오면 둘 다 통과할 수 있다. 그래서 프로필 행을 잠가 같은 사용자의 기록을
     * 줄 세운 뒤 다시 센다. 잠금은 커밋하거나 롤백할 때 풀린다. 같은 잠금 덕분에 연속 기록의 첫 행이 두 번 만들어지거나
     * 일수 갱신이 유실되지 않는다.
     *
     * 프로필 데이터를 지키려는 잠금이 아니라, 사용자마다 항상 한 행이 있는 테이블을 줄 세우기 표로 빌려 쓰는 것이다.
     * 아직 없는 식사 행은 잠글 수 없고(범위를 잠그면 갭 락끼리 데드락이 난다), users는 여러 테이블의 FK 부모라
     * 다른 흐름의 INSERT까지 함께 기다리게 되며, coins는 잔액을 바꾸는 흐름이 잠글 행이라 피했다.
     *
     * 잠금보다 먼저 일반 조회를 하면 안 된다. REPEATABLE READ는 트랜잭션의 첫 일반 조회 시점 스냅샷을 계속 보므로,
     * 앞선 요청이 커밋한 식사를 못 보고 옛 횟수로 통과시킨다.
     *
     * @param meal 저장할 식사
     * @param rewardBox 이 식사의 보상으로 뽑은 상자
     * @param rewardMailExpiresAt 보상 상자 우편의 만료 시각
     * @param language 식사를 기록한 요청의 앱 언어. 보상 상자 우편의 문구를 이 언어로 저장하고, 분석 작업에 실어 결과를 이 언어로 만든다
     * @return 저장된 [Meal]
     * @throws BusinessException [UserErrorCode.PROFILE_NOT_FOUND] 가입 시 만들어져야 할 프로필이 없는 경우
     * @throws BusinessException [com.dandi.nyummy.exception.errorcode.MealErrorCode.DAILY_COUNT_EXCEEDED] 다시 센 오늘 시도 횟수가 상한인 경우
     */
    @Transactional
    fun createMeal(meal: Meal, rewardBox: RewardBoxResult, rewardMailExpiresAt: Instant, language: Language): Meal {
        profileRepository.findProfileWithLockByUserId(meal.userId)
            ?: throw BusinessException(UserErrorCode.PROFILE_NOT_FOUND)
        mealAttemptService.validateDailyCount(meal.userId)

        mealRepository.save(meal)
        mealAnalysisJobRepository.save(MealAnalysisJob(mealId = meal.id, language = language))
        mailService.createMealRewardMail(
            userId = meal.userId,
            rewardBoxCategory = rewardBox.category,
            coinQuantity = rewardBox.coinQuantity,
            expiresAt = rewardMailExpiresAt,
            language = language,
        )
        streakService.updateStreak(meal.userId)
        return meal
    }

    /**
     * v2 식사와 인식 결과에 딸린 행들, 보상 상자 우편을 한 트랜잭션으로 저장하고 연속 기록을 갱신한다.
     * 하루 시도 횟수를 다시 세는 방식과 프로필 행을 잠그는 이유는 [createMeal]과 같다.
     *
     * 인식 결과에 따라 저장하는 것이 다르다.
     * - 음식으로 인식했으면 AI가 처음 낸 목록의 스냅샷과 음식 줄을 저장하고, [nutritionDelay] 뒤부터 가져갈 영양 작업을 만든다.
     *   그 시간 안에 사용자가 음식 목록을 보내면 이 작업을 지우고 바로 계산한다.
     * - 음식이 아니면 식사만 남긴다. 작업이 없으므로 Worker는 이 식사를 건드리지 않는다.
     * - 요청 안에서 인식을 끝내지 못했으면 바로 가져갈 인식 작업을 만든다. 음식 줄은 Worker가 인식한 뒤에 생긴다.
     *
     * 보상 상자와 연속 기록은 인식 결과와 상관없이 기록한 것으로 인정한다.
     *
     * @param meal 저장할 식사. 상태·대표 이름·입력 유형은 [outcome]에 맞춰 채워져 있어야 한다
     * @param outcome 식사 기록 요청 안에서 음식 인식을 시도한 결과
     * @param nutritionDelay 영양 작업을 가져가기까지 기다리는 시간
     * @param language 식사를 기록한 요청의 앱 언어. 보상 상자 우편의 문구를 이 언어로 저장하고, 작업에 실어 결과를 이 언어로 만든다
     * @return 저장한 음식 줄(순서대로). 음식으로 인식하지 못했으면 빈 목록
     * @throws BusinessException [UserErrorCode.PROFILE_NOT_FOUND] 가입 시 만들어져야 할 프로필이 없는 경우
     * @throws BusinessException [com.dandi.nyummy.exception.errorcode.MealErrorCode.DAILY_COUNT_EXCEEDED] 다시 센 오늘 시도 횟수가 상한인 경우
     */
    @Transactional
    fun createMealV2(
        meal: Meal,
        outcome: MealRecognitionOutcome,
        rewardBox: RewardBoxResult,
        rewardMailExpiresAt: Instant,
        nutritionDelay: Duration,
        language: Language,
    ): List<MealFood> {
        profileRepository.findProfileWithLockByUserId(meal.userId)
            ?: throw BusinessException(UserErrorCode.PROFILE_NOT_FOUND)
        mealAttemptService.validateDailyCount(meal.userId)

        mealRepository.save(meal)
        val mealFoods = when (outcome) {
            is MealRecognitionOutcome.Recognized -> {
                val mealFoods = createMealFoods(meal.id, outcome.recognition)
                val job = mealAnalysisJobRepository.save(
                    MealAnalysisJob(
                        mealId = meal.id,
                        language = language,
                        category = MealAnalysisJobCategory.NUTRITION,
                    ),
                )
                if (nutritionDelay.toSeconds() > 0) {
                    mealAnalysisJobRepository.updateLeaseExpiresAt(job.id, nutritionDelay.toSeconds())
                }
                mealFoods
            }

            MealRecognitionOutcome.NotFood -> emptyList()

            is MealRecognitionOutcome.Deferred -> {
                mealAnalysisJobRepository.save(
                    MealAnalysisJob(
                        mealId = meal.id,
                        language = language,
                        category = MealAnalysisJobCategory.RECOGNITION,
                    ),
                )
                emptyList()
            }
        }
        mailService.createMealRewardMail(
            userId = meal.userId,
            rewardBoxCategory = rewardBox.category,
            coinQuantity = rewardBox.coinQuantity,
            expiresAt = rewardMailExpiresAt,
            language = language,
        )
        streakService.updateStreak(meal.userId)

        return mealFoods
    }

    /**
     * 인식 결과를 스냅샷(AI가 처음 낸 목록)과 음식 줄(사용자가 고치는 지금 값)로 저장한다.
     * 음식 줄은 짝이 되는 스냅샷 줄을 가리키고, 순서는 인식이 낸 순서(맨 앞이 대표 음식)다.
     */
    private fun createMealFoods(mealId: Long, recognition: MealRecognitionResult): List<MealFood> {
        val mealSnapshot = mealSnapshotRepository.save(
            MealSnapshot(
                mealId = mealId,
                name = recognition.name,
                recognitionCategory = MealRecognitionCategory.SYNC,
                model = recognition.model,
                promptVersion = recognition.promptVersion,
                inputCategory = recognition.inputCategory,
            ),
        )
        val mealFoodSnapshots = mealFoodSnapshotRepository.saveAll(
            recognition.foods.mapIndexed { index, food ->
                MealFoodSnapshot(
                    mealSnapshotId = mealSnapshot.id,
                    displayOrder = index + 1,
                    name = food.name,
                    portion = food.portion,
                )
            },
        )

        return mealFoodRepository.saveAll(
            mealFoodSnapshots.map { mealFoodSnapshot ->
                MealFood(
                    mealId = mealId,
                    mealFoodSnapshotId = mealFoodSnapshot.id,
                    displayOrder = mealFoodSnapshot.displayOrder,
                    name = mealFoodSnapshot.name,
                    portion = mealFoodSnapshot.portion,
                )
            },
        )
    }
}
