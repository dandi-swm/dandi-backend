package com.dandi.nyummy.admin.config

import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 어드민 서비스가 빌드된 admin-web을 /admin/ 아래로 내려준다. 팀원은 SSM 터널만 열고 브라우저로 쓴다.
 *
 * 컨트롤러가 리소스 핸들러보다 먼저 매칭되므로 /admin/api API와 겹치지 않는다. 앱 서비스에서는 만들지 않는다.
 *
 * 배포마다 index.html이 새 assets를 가리키므로 캐시하지 않는다(no-cache는 매번 재검증). 내부 도구라 assets를
 * 오래 캐시해서 얻을 이득도 작다.
 */
@Configuration
@ConditionalOnAdminEnabled
class AdminWebConfig(private val adminProperties: AdminProperties) : WebMvcConfigurer {

    override fun addViewControllers(registry: ViewControllerRegistry) {
        // 화면은 /admin/ 기준으로 빌드돼 있어 끝 슬래시가 없으면 상대 경로가 어긋난다.
        registry.addRedirectViewController(AdminPaths.WEB_PREFIX, "${AdminPaths.WEB_PREFIX}/")
        // 리소스 핸들러는 빈 경로(/admin/)를 리졸버에 넘기기 전에 거절하므로 첫 화면은 index.html로 직접 넘긴다.
        registry.addViewController(
            "${AdminPaths.WEB_PREFIX}/",
        ).setViewName("forward:${AdminPaths.WEB_PREFIX}/index.html")
    }

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("${AdminPaths.WEB_PREFIX}/**")
            .addResourceLocations(adminProperties.webLocation)
            .setCacheControl(CacheControl.noCache())
            .resourceChain(false)
            .addResolver(AdminWebFallbackResourceResolver())
    }
}
