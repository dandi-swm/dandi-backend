package com.dandi.nyummy.cat.calculator

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate
import kotlin.test.assertEquals

class CatWeightCalculatorTest {

    private val today = LocalDate.of(2026, 9, 27)

    /**
     * 주기 3일 기준. 완전히 지난 구간만 센다.
     * 남은 일수(나머지)는 세지 않으므로 다음 평가로 넘어간다.
     */
    @ParameterizedTest(name = "{0}일 전에 평가 → 지난 구간 {1}개")
    @CsvSource(
        "0, 0", // 오늘 이미 평가함
        "1, 0",
        "2, 0", // 주기 직전
        "3, 1", // 주기 정확히
        "5, 1", // 1구간 + 남은 2일
        "6, 2",
        "8, 2", // 2구간 + 남은 2일
        "9, 3",
        "12, 4",
        "30, 10", // 창 상한은 호출자(CatService)의 책임이라 여기서는 그대로 센다
    )
    fun `마지막 평가일로부터 지난 평가 구간의 수를 센다`(daysAgo: Long, expected: Int) {
        // given
        val lastEvaluatedDate = today.minusDays(daysAgo)

        // when
        val result = calculateElapsedIntervals(lastEvaluatedDate, today, intervalDays = 3)

        // then
        assertEquals(expected, result)
    }

    @Test
    fun `마지막 평가일이 미래면 음수를 반환해, 호출자가 평가를 건너뛸 수 있게 한다`() {
        // given: 시계 역전이나 timezone 변경으로 생길 수 있다. 0으로 보정하지 않는다.
        val lastEvaluatedDate = today.plusDays(3)

        // when
        val result = calculateElapsedIntervals(lastEvaluatedDate, today, intervalDays = 3)

        // then
        assertEquals(-1, result)
    }

    /**
     * 목표 2000, 허용 오차 0.2 기준. 증가 임계는 2400(120%) 이상이다.
     *
     * 기록이 없으면 -1, 과식이면 +1로 현재 체형과 무관하다.
     * 그 밖(적정·소식·분석 실패만 있음)은 보통냥(0) 쪽으로 한 칸이다.
     */
    @ParameterizedTest(name = "기록 {0}, 섭취 {1}, 목표 {2}, 현재 체형 {3} → step {4}")
    @CsvSource(
        // 기록 없음 — 체형과 무관하게 빠진다
        "false, 0, 2000, 0, -1",
        "false, 0, 2000, 2, -1", // 뚱냥이도 -1
        "false, 0, 2000, -2, -1", // 홀쭉냥이 굶으면 -1 (하한은 호출자가 잘라낸다)
        // 과식 — 체형과 무관하게 찐다
        "true, 2400, 2000, 0, 1", // 증가 임계 정확히 (이상이므로 포함)
        "true, 2500, 2000, 0, 1", // 증가 임계 초과
        "true, 2500, 2000, 2, 1", // 뚱냥이도 +1 (상한은 호출자가 잘라낸다)
        "true, 2500, 2000, -2, 1", // 홀쭉냥이 과식하면 찐다
        // 적정 — 보통냥 쪽으로 한 칸씩
        "true, 2000, 2000, 2, -1", // 뚱냥이 → 통통냥
        "true, 2000, 2000, 1, -1", // 통통냥 → 보통냥
        "true, 2000, 2000, 0, 0", // 보통냥은 유지
        "true, 2000, 2000, -1, 1", // 날씬냥 → 보통냥
        "true, 2000, 2000, -2, 1", // 홀쭉냥 → 날씬냥
        "true, 2399, 2000, 2, -1", // 증가 임계 직전도 적정
        // 소식 — 빠지지 않고 보통냥 쪽으로 한 칸
        "true, 500, 2000, 0, 0",
        "true, 500, 2000, -1, 1",
        "true, 500, 2000, 2, -1",
        // 분석 실패만 있음 — 기록은 있고 섭취량은 0
        "true, 0, 2000, 0, 0",
        "true, 0, 2000, -2, 1",
    )
    fun `기록 여부와 섭취량, 현재 체형으로 체형 변화 단계를 계산한다`(
        hasMeal: Boolean,
        totalCalory: Int,
        targetCalory: Int,
        currentWeight: Int,
        expected: Int,
    ) {
        // when
        val step =
            calculateWeightStep(hasMeal, totalCalory, targetCalory, tolerance = 0.2, currentWeight = currentWeight)

        // then
        assertEquals(expected, step)
    }
}
