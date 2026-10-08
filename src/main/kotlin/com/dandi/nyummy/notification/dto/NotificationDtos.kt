@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.notification.dto

import com.dandi.nyummy.notification.enum.DevicePlatform

data class CreateDeviceTokenRequest(val token: String, val platform: DevicePlatform)
