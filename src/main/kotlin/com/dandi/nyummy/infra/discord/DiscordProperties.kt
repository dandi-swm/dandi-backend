package com.dandi.nyummy.infra.discord

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * @property webhookUrl 운영자 채널의 Discord Webhook URL. 비어 있으면 보내지 않는다.
 */
@ConfigurationProperties(prefix = "app.discord")
class DiscordProperties(val webhookUrl: String, val connectTimeout: Duration, val readTimeout: Duration)
