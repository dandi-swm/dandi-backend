package com.dandi.nyummy.infra.ai.nutrition

/**
 * 영양 분석이 실패한 이유의 분류. 분석 제공자(Gemini 등)와 상관없이 같은 값으로 나눈다.
 * 재시도할지, 어떤 알림을 보낼지는 이 값을 받은 쪽이 정한다.
 */
enum class AnalysisFailureCategory {

    /** 잠시 뒤 다시 하면 될 수 있는 실패: 요청 한도 초과, 서버 오류, 타임아웃, 이미지·아이콘 조회 실패 */
    UNAVAILABLE,

    /** 분석은 됐지만 음식 사진이 아니다 */
    NOT_FOOD,

    /** 응답이 비었거나 약속한 형식으로 해석되지 않는다 */
    INVALID_RESPONSE,

    /** 요청 자체가 거부됐다: 4xx(요청 한도 초과·설정 오류 제외), 분석할 이미지가 없거나 비었다 */
    INVALID_REQUEST,

    /**
     * 우리 쪽 설정 때문에 모든 요청이 거부된다: 키·권한 오류, 모델 이름 오류, 이미지 저장소 권한 오류.
     * 요청 하나의 문제가 아니라 설정을 고치면 그대로 성공할 요청이다.
     */
    MISCONFIGURED,
}
