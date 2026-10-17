package com.dandi.nyummy.internal.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.internal")
data class InternalProperties(@field:NotBlank val batchKey: String)
