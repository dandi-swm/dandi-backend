package com.dandi.nyummy.admin.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty

/**
 * 어드민 서비스(app.admin.enabled=true)에서만 빈을 만든다. 앱 서비스에는 어드민 컨트롤러·서비스·Google 로그인이 없다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnBooleanProperty("app.admin.enabled")
annotation class ConditionalOnAdminEnabled
