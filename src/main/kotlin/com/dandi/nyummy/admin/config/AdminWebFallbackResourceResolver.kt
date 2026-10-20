package com.dandi.nyummy.admin.config

import org.springframework.core.io.Resource
import org.springframework.web.servlet.resource.PathResourceResolver

/**
 * 어드민 웹(SPA)의 정적 파일을 찾고, 없으면 index.html을 내려준다. 첫 화면(/admin/)은 [AdminWebConfig]가 따로 넘긴다.
 *
 * /admin/inquiries/12 같은 화면 주소는 파일이 아니라 브라우저 라우터가 그리는 경로다. 새로고침하거나 주소로 바로
 * 들어오면 서버에는 그런 파일이 없으므로 index.html을 내려줘 라우터가 이어받게 한다.
 *
 * 대신 아래는 index.html로 대신하지 않고 그대로 404로 둔다.
 * - API 경로(/admin/api/...): 없는 API를 화면으로 응답하면 클라이언트가 JSON 대신 HTML을 받는다
 * - 확장자가 있는 경로(assets/old.js 등): 없는 파일을 HTML로 응답하면 브라우저가 스크립트로 해석하다 깨진다
 */
class AdminWebFallbackResourceResolver : PathResourceResolver() {

    companion object {
        private const val INDEX = "index.html"

        // 리소스 핸들러 아래 상대 경로라 /admin/api/ 은 "api/"로 들어온다.
        private val API_PATH = AdminPaths.PREFIX.removePrefix("${AdminPaths.WEB_PREFIX}/") + "/"
    }

    override fun getResource(resourcePath: String, location: Resource): Resource? {
        super.getResource(resourcePath, location)?.let { return it }

        val isApi = resourcePath.startsWith(API_PATH)
        val isFile = resourcePath.substringAfterLast('/').contains('.')
        if (isApi || isFile) {
            return null
        }

        return super.getResource(INDEX, location)
    }
}
