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

    // Flyway
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // AWS SDK
    implementation("aws.sdk.kotlin:s3:1.4.0")
    implementation("aws.sdk.kotlin:sesv2:1.4.0")

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

    // jjwt 설정
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // spring-doc
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")

    runtimeOnly("org.bouncycastle:bcprov-jdk18on:1.82")

    // 이미지 metadata 추출
    implementation("com.drewnoakes:metadata-extractor:2.19.0")
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
