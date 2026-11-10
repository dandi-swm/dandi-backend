package com.dandi.nyummy.infra.ai.report

/**
 * 리포트 문장 생성이 실패한 이유의 분류. 재시도할지는 이 값을 받은 쪽이 정한다.
 */
enum class ReportGenerationFailureCategory {

    /** 잠시 뒤 다시 하면 될 수 있는 실패: 요청 한도 초과, 서버 오류, 타임아웃 */
    UNAVAILABLE,

    /** 응답이 비었거나, 약속한 형식이 아니거나, 길이·개수·숫자 금지 규칙을 어겼다. 다시 만들면 맞을 수 있다 */
    INVALID_RESPONSE,

    /** 요청 자체가 거부됐다: 4xx(요청 한도 초과·설정 오류 제외) */
    INVALID_REQUEST,

    /** 우리 쪽 설정(키·권한·모델 이름) 때문에 모든 요청이 거부된다. 설정을 고치면 그대로 성공할 요청이다 */
    MISCONFIGURED,
}
