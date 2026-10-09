package com.dandi.nyummy.cat.calculator

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate

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
    @DisplayName("마지막 평가일로부터 지난 평가 구간의 수를 센다")
    fun calculateElapsedIntervals_elapsed(daysAgo: Long, expected: Int) {
        // given
        val lastEvaluatedDate = today.minusDays(daysAgo)

        // when
        val result = calculateElapsedIntervals(lastEvaluatedDate, today, intervalDays = 3)

        // then
        assertThat(result).isEqualTo(expected)
    }

    @Test
    @DisplayName("마지막 평가일이 미래면 음수를 반환해, 호출자가 평가를 건너뛸 수 있게 한다")
    fun calculateElapsedIntervals_future() {
        // given: 시계 역전이나 timezone 변경으로 생길 수 있다. 0으로 보정하지 않는다.
        val lastEvaluatedDate = today.plusDays(3)

        // when
        val result = calculateElapsedIntervals(lastEvaluatedDate, today, intervalDays = 3)

        // then
        assertThat(result).isEqualTo(-1)
    }

    /**
     * 목표 2000, 허용 오차 0.2 기준.
     * 증가 임계는 2400(120%) 이상, 감소 임계는 1600(80%) 미만이고, 그 사이가 적정이다.
     *
     * 과식·소식은 현재 체형과 무관하게 ±1이고, 적정은 보통냥(0) 쪽으로 한 칸이다.
     */
    @ParameterizedTest(name = "섭취 {0}, 목표 {1}, 현재 체형 {2} → step {3}")
    @CsvSource(
        // 과식 — 체형과 무관하게 찐다
        "2400, 2000, 0, 1", // 증가 임계 정확히 (이상이므로 포함)
        "2500, 2000, 0, 1", // 증가 임계 초과
        "2500, 2000, 2, 1", // 뚱냥이도 +1 (상한은 호출자가 잘라낸다)
        "2500, 2000, -2, 1", // 홀쭉냥이 과식하면 찐다
        // 소식 — 체형과 무관하게 빠진다
        "1599, 2000, 0, -1", // 감소 임계 직전
        "0, 2000, 0, -1", // 미기록은 소식으로 본다
        "0, 2000, -2, -1", // 홀쭉냥이 굶으면 -1 (하한은 호출자가 잘라낸다)
        // 적정 — 보통냥 쪽으로 한 칸씩
        "2000, 2000, 2, -1", // 뚱냥이 → 통통냥
        "2000, 2000, 1, -1", // 통통냥 → 보통냥
        "2000, 2000, 0, 0", // 보통냥은 유지
        "2000, 2000, -1, 1", // 날씬냥 → 보통냥
        "2000, 2000, -2, 1", // 홀쭉냥 → 날씬냥
        "2399, 2000, 2, -1", // 증가 임계 직전도 적정
        "1600, 2000, 2, -1", // 감소 임계 정확히 (미만이 아니므로 적정)
    )
    @DisplayName("섭취량과 현재 체형으로 체형 변화 단계를 계산한다")
    fun calculateWeightStep_boundaries(totalCalory: Int, targetCalory: Int, currentWeight: Int, expected: Int) {
        // when
        val step = calculateWeightStep(totalCalory, targetCalory, tolerance = 0.2, currentWeight = currentWeight)

        // then
        assertThat(step).isEqualTo(expected)
    }
}
