package com.dandi.nyummy.config

import com.dandi.nyummy.header.CommonHeaderArgumentResolver
import com.dandi.nyummy.language.LanguageArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(CommonHeaderArgumentResolver())
        resolvers.add(LanguageArgumentResolver())
    }
}
