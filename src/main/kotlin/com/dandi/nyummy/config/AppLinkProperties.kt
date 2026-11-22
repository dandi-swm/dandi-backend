package com.dandi.nyummy.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.app-link")
class AppLinkProperties(
    baseUrl: String,
    homeUrl: String,
    signupUrl: String,
    mealDetailUrl: String,
    onboardingUrl: String,
    inquiryDetailUrl: String,
    reportUrl: String = "/reports",
    appUpdateUrl: String = "/app-update",
) {
    val home: String = baseUrl + homeUrl
    val signup: String = baseUrl + signupUrl
    private val mealDetail: String = baseUrl + mealDetailUrl
    val onboarding: String = baseUrl + onboardingUrl
    private val inquiryDetail: String = baseUrl + inquiryDetailUrl

    // 리포트 목록. 상세는 뒤에 /{reportId}를 붙인다.
    val reports: String = baseUrl + reportUrl

    // 앱 업데이트 알림의 링크. 스토어는 앱이 자기 플랫폼에 맞게 연다.
    val appUpdate: String = baseUrl + appUpdateUrl

    fun createMealDetailLink(mealId: Long): String = "$mealDetail/$mealId"

    fun createInquiryDetailLink(inquiryId: Long): String = "$inquiryDetail/$inquiryId"

    fun createReportDetailLink(reportId: Long): String = "$reports/$reportId"
}
