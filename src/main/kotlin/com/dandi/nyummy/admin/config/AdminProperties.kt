package com.dandi.nyummy.admin.config

import jakarta.validation.constraints.NotEmpty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 어드민 설정. 어드민은 같은 이미지를 별도 ECS 서비스(nyummy-prod-admin)로 띄우고, 그 서비스에서만 enabled를 켠다.
 *
 * @property enabled 어드민 빈(컨트롤러·Google 로그인·웹 화면)을 만든다. 앱 서비스는 기본값(false)이라 어드민 설정이 필요 없다
 * @property allowedOrigins 어드민 API를 브라우저에서 쿠키와 함께 호출할 수 있는 출처. 여기 있는 사이트는
 *   어드민 쿠키로 요청을 보내고 응답을 읽을 수 있으므로, 와일드카드 없이 어드민 웹 주소만 정확히 둔다.
 *   개발 서버(localhost:3000)와 prod 어드민 서비스로 여는 SSM 터널(localhost:18080) 두 곳이고 모든 환경에서 같다
 * @property googleClientId 어드민 웹용 Google OAuth 클라이언트 ID. enabled일 때만 필요하다
 * @property webLocation 빌드된 admin-web(index.html, assets)이 있는 위치. Docker 이미지는 /app/admin-web에 넣는다
 */
@Validated
@ConfigurationProperties(prefix = "app.admin")
data class AdminProperties(
    val enabled: Boolean = false,

    @field:NotEmpty
    val allowedOrigins: List<String> = listOf("http://localhost:3000", "http://localhost:18080"),

    val googleClientId: String? = null,

    val webLocation: String = "file:/app/admin-web/",
)
