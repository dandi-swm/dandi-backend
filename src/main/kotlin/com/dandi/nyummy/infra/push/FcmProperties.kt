package com.dandi.nyummy.infra.push

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.fcm")
class FcmProperties(val credentialsBase64: String, val dryRun: Boolean)
