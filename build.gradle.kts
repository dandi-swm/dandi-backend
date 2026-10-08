plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.3.21"

    id("com.diffplug.spotless") version "8.8.0"
    id("com.github.jakemarsden.git-hooks") version "0.0.2"
}

group = "com.dandi"
version = "0.0.1-SNAPSHOT"
description = "nyummy"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Mock (Kotlin 전용)
    testImplementation("io.mockk:mockk:1.14.11")

    // Flyway
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // AWS SDK
    implementation("aws.sdk.kotlin:s3:1.4.0")
    implementation("aws.sdk.kotlin:sesv2:1.4.0")

    // SMTP 메일 발송 (JavaMailSender)
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // .env 파일 파싱용 라이브러리
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")

    // 입력 검증
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Apache Tika (파일 시그니처 DB)
    implementation("org.apache.tika:tika-core:3.3.1")

    // HTTP 클라이언트 (RestClient)
    implementation("org.springframework.boot:spring-boot-starter-restclient")

    // Spring-Security
    implementation("org.springframework.boot:spring-boot-starter-security")

    // OIDC ID 토큰 검증 (NimbusJwtDecoder, JWKS)
    implementation("org.springframework.security:spring-security-oauth2-jose")

    // jjwt 설정
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // spring-doc
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")

    runtimeOnly("org.bouncycastle:bcprov-jdk18on:1.82")

    // 이미지 metadata 추출
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    // 로컬 캐시 Caffeine
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Testcontainers (버전은 Boot BOM이 관리 — 4.1.0 기준 2.0.5)
    // 2.0부터 모듈 아티팩트에 testcontainers- 접두사가 붙는다. (구: junit-jupiter, mysql)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")

    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // FCM (firebase-admin)
    implementation("com.google.firebase:firebase-admin:9.10.0")

    // 관측(OpenTelemetry): 메트릭·트레이스 OTLP 전송
    // OTel 기본 전송기(okhttp sender)는 OkHttp를 5.3.x로 끌어올려 AWS Kotlin SDK(5.0.0-alpha 기준 빌드)를
    // 깨뜨린다(ClassNotFoundException: okhttp3.ConnectionListener). JDK HttpClient 전송기로 바꾼다.
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    implementation("io.opentelemetry:opentelemetry-exporter-sender-jdk")

    // 로그 OTLP 전송. Boot BOM 밖이라 버전을 직접 맞춘다.
    // 2.28.0-alpha가 Boot 4.1.0의 OTel SDK 1.62.0을 요구한다(2.29+는 1.63+).
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.0-alpha")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**", "**/generated/**")
        ktlint("1.8.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.8.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

gitHooks {
    setHooks(
        mapOf(
            "pre-commit" to "spotlessApply stageChanges",
        ),
    )
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.register<Exec>("stageChanges") {
    commandLine("bash", "-c", "git diff --diff-filter=d --name-only --cached -z | xargs -0 -r git add --")
}

tasks.named("stageChanges") {
    mustRunAfter("spotlessApply")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
