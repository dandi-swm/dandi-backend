package com.dandi.nyummy.admin.config

import jakarta.validation.constraints.NotEmpty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 어드민 웹 설정.
 *
 * @property allowedOrigins 어드민 API를 브라우저에서 쿠키와 함께 호출할 수 있는 출처. 여기 있는 사이트는
 *   어드민 쿠키로 요청을 보내고 응답을 읽을 수 있으므로, 와일드카드 없이 어드민 웹 주소만 정확히 둔다
 */
@Validated
@ConfigurationProperties(prefix = "app.admin")
data class AdminProperties(
    @field:NotEmpty
    val allowedOrigins: List<String>,
)
