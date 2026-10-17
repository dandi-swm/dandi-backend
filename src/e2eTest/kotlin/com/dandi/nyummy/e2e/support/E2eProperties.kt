package com.dandi.nyummy.e2e.support

// E2E 실행 설정. Jenkins는 withCredentials로, 로컬은 셸이나 실행 구성의 환경변수로 넣는다.
// 기본값을 두지 않는다. (값이 없을 때 엉뚱한 서버를 조용히 테스트하는 것보다 바로 실패하는 편이 낫다.)
object E2eProperties {
    val baseUrl: String by lazy { getRequiredEnv("E2E_BASE_URL").removeSuffix("/") }
    val email: String by lazy { getRequiredEnv("E2E_USER_EMAIL") }
    val password: String by lazy { getRequiredEnv("E2E_USER_PASSWORD") }

    // 남의 리소스 접근(403)을 확인할 때만 쓰는 두 번째 고정 계정. dev 전용이라 prod 스모크에는 필요 없다.
    val otherEmail: String by lazy { getRequiredEnv("E2E_OTHER_USER_EMAIL") }
    val otherPassword: String by lazy { getRequiredEnv("E2E_OTHER_USER_PASSWORD") }

    private fun getRequiredEnv(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name 환경변수가 없습니다")
}
