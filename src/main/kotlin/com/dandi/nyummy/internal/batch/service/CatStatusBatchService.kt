package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.cat.service.CatService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class CatStatusBatchService(private val catService: CatService, private val catRepository: CatRepository) {

    private val logger = LoggerFactory.getLogger(CatStatusBatchService::class.java)

    /**
     * 모든 고양이의 체형을 평가한다.
     *
     * 트랜잭션을 걸지 않는다. [CatService.updateCatWeight]가 건별로 커밋하므로,
     * 한 고양이의 갱신이 실패해도 나머지는 반영된다.
     */
    fun updateCatWeights() {
        val cats = catRepository.findAll()

        for (cat in cats) {
            catService.updateCatWeight(cat)
        }
    }

    fun sendDailyRetentionPushes() {
        val cats = catRepository.findAll()

        // TODO: 사용자별 timezone으로 수정
        val zone = ZoneId.of("Asia/Seoul")
        val today = LocalDate.now(zone)

        val dailyPush = ArrayList<Long>()
        val loseWeightPush = ArrayList<Long>()
        val sleepPush = ArrayList<Long>()

        for (cat in cats) {
            val lastMealAt = cat.lastMealAt.atZone(zone).toLocalDate()
            val daysDiff = ChronoUnit.DAYS.between(lastMealAt, today)

            logger.info("catId: ${cat.id} lastMealAt: $lastMealAt, today: $today, daysDiff: $daysDiff")

            if (daysDiff <= 0L) {
                continue
            }

            when (daysDiff) {
                14L -> {
                    // TODO: FCM PUSH ("냐미가 기다리다 지쳐 잠들었어요💤 한 끼 기록하고 깨우기!")
                    sleepPush.add(cat.id)
                }

                3L, 6L, 9L, 12L -> {
                    // TODO: FCM PUSH ("냐미가 ${daysDiff}일째 굶어서 홀쭉해지고 있어요ㅠㅠ🐾")
                    loseWeightPush.add(cat.id)
                }

                else -> {
                    // TODO: FCM PUSH ("오늘 하루 냐미 밥을 깜빡했다냥! 밥 챙겨주라냥🐾")
                    dailyPush.add(cat.id)
                }
            }
        }

        logger.info("dailyPush: $dailyPush")
        logger.info("loseWeightPush: $loseWeightPush")
        logger.info("sleepPush: $sleepPush")
    }
}
