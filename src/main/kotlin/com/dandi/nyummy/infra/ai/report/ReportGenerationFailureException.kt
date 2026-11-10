package com.dandi.nyummy.infra.ai.report

/**
 * [ReportGenerationClient] 구현체가 리포트 문장을 만들지 못했을 때 던지는 예외.
 * 제공자마다 다른 오류를 [category]로 번역해, 호출하는 쪽이 제공자를 몰라도 되게 한다.
 */
class ReportGenerationFailureException(
    val category: ReportGenerationFailureCategory,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
