package com.dandi.nyummy.infra.storage.s3

import aws.sdk.kotlin.services.s3.S3Client
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class S3Config(@Value("\${AWS_REGION}") private val region: String) {
    // credentialsProvider를 지정하지 않으면 SDK 기본 체인을 사용한다.
    // 운영: ECS 태스크 역할 / dev: EC2 인스턴스 역할 / 로컬: ~/.aws 프로필(SSO)
    @Bean
    fun s3Client(): S3Client = S3Client {
        this.region = this@S3Config.region
    }
}
