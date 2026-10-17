package com.dandi.nyummy.e2e.home

import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.oneOf
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class HomeE2eTest {
    // 여러 도메인(사용자·식사·문의·마이룸)을 한 번에 묶는 가장 복잡한 조회 경로다.
    @Smoke
    @Test
    fun `홈 화면 값을 한 번에 조회한다`() {
        val home = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/home")
        } Then {
            statusCode(200)
            body(
                "keySet()",
                hasItems("user", "streak", "todayMealSummary", "hasUnreadInquiryAnswer", "equippedRoomItems"),
            )
            body("user.keySet()", hasItems("coin"))
            body("streak.keySet()", hasItems("streakDays", "recordsUntilNextReward"))
            body(
                "todayMealSummary.keySet()",
                hasItems(
                    "todayRecordedCount",
                    "todayAttemptCount",
                    "todayMaxAttemptCount",
                    "todayCurrentCalory",
                    "todayTargetCalory",
                ),
            )
            body("hasUnreadInquiryAnswer", oneOf(true, false))
            // 마이룸은 아직 고정 응답이라 원소 구조는 검증하지 않는다. 실제 구현되면 Room 테스트와 함께 추가한다.
            body("equippedRoomItems", instanceOf<Any>(List::class.java))
        } Extract {
            jsonPath()
        }

        val recordedCount = home.getInt("todayMealSummary.todayRecordedCount")
        val attemptCount = home.getInt("todayMealSummary.todayAttemptCount")
        val maxAttemptCount = home.getInt("todayMealSummary.todayMaxAttemptCount")
        val targetCalory = home.getInt("todayMealSummary.todayTargetCalory")

        // 삭제·분석 실패한 식사도 시도에 포함되므로, 남아 있는 식사 수는 시도 횟수를 넘을 수 없다.
        assertTrue(recordedCount <= attemptCount, "recorded=$recordedCount, attempt=$attemptCount")
        // 상한에 닿으면 생성이 막히므로 시도 횟수는 상한을 넘을 수 없다.
        assertTrue(attemptCount <= maxAttemptCount, "attempt=$attemptCount, max=$maxAttemptCount")
        // 목표 칼로리는 프로필 신체 정보로 계산한다. 고정 계정은 가입 때 신체 정보를 채워 두었다.
        assertTrue(targetCalory > 0, "target=$targetCalory")
    }
}
