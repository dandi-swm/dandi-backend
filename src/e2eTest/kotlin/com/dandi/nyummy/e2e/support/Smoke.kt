package com.dandi.nyummy.e2e.support

import org.junit.jupiter.api.Tag

// prod blue/green 전환 전에 돌리는 최소 시나리오. -Pe2eTags=smoke로 이것만 실행한다.
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("smoke")
annotation class Smoke
