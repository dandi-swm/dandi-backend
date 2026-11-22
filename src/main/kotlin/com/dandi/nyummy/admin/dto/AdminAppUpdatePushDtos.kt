@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.admin.dto

import com.dandi.nyummy.language.enum.Language

/** @property language 기기 앱 언어. V0.53 전에 등록한 기기 토큰은 null이고, 원본 언어로 보낸다. */
data class AppUpdatePushTargetResult(val userId: Long, val token: String, val language: Language?)
