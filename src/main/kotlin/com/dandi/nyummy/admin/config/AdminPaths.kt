package com.dandi.nyummy.admin.config

/**
 * 어드민 API 경로 접두. /api 밖에 두어 ALB 화이트리스트(/api/\*, /actuator/health)에 포함되지 않게 한다.
 * 쿠키 Path, Origin 필터, 권한 규칙, CORS가 모두 이 값을 써야 서로 어긋나지 않는다.
*/
object AdminPaths {
    const val PREFIX = "/admin/v1"
}
