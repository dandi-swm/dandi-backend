package com.dandi.nyummy.cat.calculator

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate
import java.time.ZoneId

class CatWeightCalculatorTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 9, 27)

    @Test
    @DisplayName("마지막 변화일로부터 주기만큼 지났으면 변화 대상이다")
    fun isWeightUpdateDue_exactlyInterval() {
        // given: 3일 전에 변화, 주기 3일
        val lastUpdated = LocalDate.of(2026, 9, 24).atStartOfDay(zone).toInstant()

        // when
        val result = isWeightUpdateDue(lastUpdated, today, zone, intervalDays = 3)

        // then
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("마지막 변화일로부터 주기를 넘겼으면 변화 대상이다")
    fun isWeightUpdateDue_overInterval() {
        // given: 10일 전에 평가, 주기 3일
        val lastUpdated = LocalDate.of(2026, 9, 17).atStartOfDay(zone).toInstant()

        // when
        val result = isWeightUpdateDue(lastUpdated, today, zone, intervalDays = 3)

        // then
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("마지막 변화일로부터 주기가 지나지 않았으면 변화 대상이 아니다")
    fun isWeightUpdateDue_beforeInterval() {
        // given: 2일 전에 평가, 주기 3일
        val lastUpdated = LocalDate.of(2026, 9, 25).atStartOfDay(zone).toInstant()

        // when
        val result = isWeightUpdateDue(lastUpdated, today, zone, intervalDays = 3)

        // then
        assertThat(result).isFalse()
    }

    @Test
    @DisplayName("같은 날 다시 변화하려 하면 변화 대상이 아니다")
    fun isWeightUpdateDue_sameDay() {
        // given: 오늘 이미 평가함
        val lastUpdated = today.atStartOfDay(zone).toInstant()

        // when
        val result = isWeightUpdateDue(lastUpdated, today, zone, intervalDays = 3)

        // then
        assertThat(result).isFalse()
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
