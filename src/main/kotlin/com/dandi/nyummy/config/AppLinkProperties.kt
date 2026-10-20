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
) {
    val home: String = baseUrl + homeUrl
    val signup: String = baseUrl + signupUrl
    private val mealDetail: String = baseUrl + mealDetailUrl
    val onboarding: String = baseUrl + onboardingUrl
    private val inquiryDetail: String = baseUrl + inquiryDetailUrl

    fun createMealDetailLink(mealId: Long): String = "$mealDetail/$mealId"

    fun createInquiryDetailLink(inquiryId: Long): String = "$inquiryDetail/$inquiryId"
}
