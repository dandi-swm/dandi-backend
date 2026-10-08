package com.dandi.nyummy.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 앱으로 이동시키는 링크(App Links). 로그인 리다이렉트와 푸시 알림이 같은 주소 체계를 쓴다.
 *
 * baseUrl과 경로를 나눠 받아 전체 URL을 미리 만들어 둔다. 도메인이 바뀌면 설정 한 줄만 고치면 되고,
 * 호출부는 완성된 주소만 다룬다.
 *
 * App Links는 도메인 검증이 끝난 플랫폼에서만 앱을 연다. Android는
 * `/.well-known/assetlinks.json`, iOS는 `/.well-known/apple-app-site-association`가 필요하다.
 * 검증되지 않은 플랫폼에서는 앱 대신 브라우저가 열리므로, 도메인을 바꿀 때는 그 파일도 함께 옮겨야 한다.
 */
@ConfigurationProperties(prefix = "app.app-link")
class AppLinkProperties(baseUrl: String, homeUrl: String, signupUrl: String) {
    val home: String = baseUrl + homeUrl
    val signup: String = baseUrl + signupUrl
}
