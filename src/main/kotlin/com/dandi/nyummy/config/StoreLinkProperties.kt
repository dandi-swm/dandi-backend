package com.dandi.nyummy.config

import com.dandi.nyummy.notification.enum.DevicePlatform
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 플랫폼별 스토어 페이지. 앱 업데이트 알림을 누르면 이 링크를 연다.
 */
@ConfigurationProperties(prefix = "app.store-link")
class StoreLinkProperties(private val android: String, private val ios: String) {
    fun getStoreLink(platform: DevicePlatform): String = when (platform) {
        DevicePlatform.ANDROID -> android
        DevicePlatform.IOS -> ios
    }
}
