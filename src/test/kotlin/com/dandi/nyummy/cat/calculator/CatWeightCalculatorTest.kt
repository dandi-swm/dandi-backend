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
     * 증가 임계는 2400(120%) 이상, 감소 임계는 1600(80%) 미만이다.
     */
    @ParameterizedTest(name = "섭취 {0}, 목표 {1} → step {2}")
    @CsvSource(
        "2400, 2000, 1", // 증가 임계 정확히 (이상이므로 포함)
        "2399, 2000, 0", // 증가 임계 직전
        "2500, 2000, 1", // 증가 임계 초과
        "2000, 2000, 0", // 목표와 동일
        "1600, 2000, 0", // 감소 임계 정확히 (미만이 아니므로 유지)
        "1599, 2000, -1", // 감소 임계 직전
        "0, 2000, -1", // 미기록
    )
    @DisplayName("섭취량과 목표 섭취량을 비교해 체형 변화 단계를 계산한다")
    fun calculateWeightStep_boundaries(totalCalory: Int, targetCalory: Int, expected: Int) {
        // when
        val step = calculateWeightStep(totalCalory, targetCalory, tolerance = 0.2)

        // then
        assertThat(step).isEqualTo(expected)
    }
}
