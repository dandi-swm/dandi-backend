package com.dandi.nyummy.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.auth")
class AuthProperties(val loginRedirectUrl: String)
