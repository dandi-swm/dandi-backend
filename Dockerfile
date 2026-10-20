# syntax=docker/dockerfile:1
# 0단계: 어드민 웹 빌드. 어드민 서비스(app.admin.enabled=true)가 /admin/ 아래로 내려준다.
# 앱 서비스 이미지에도 같이 들어가지만 앱 서비스에서는 내려주지 않는다.
# package.json·lock 파일을 먼저 복사해 의존성 설치 레이어를 캐시한다.
FROM node:24-slim AS admin-web
WORKDIR /admin-web

COPY admin-web/package.json admin-web/package-lock.json ./
RUN npm ci

COPY admin-web/ ./
RUN npm run build


# 1단계: 애플리케이션 빌드
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./

RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY . .
RUN ./gradlew bootJar -x test --no-daemon


# 2단계: RDS 인증서 준비
FROM eclipse-temurin:25-jdk AS certs

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl openssl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 기본 Java 인증서 저장소를 복사
RUN cp "$JAVA_HOME/lib/security/cacerts" /tmp/rds-cacerts

# AWS 공식 서울 리전 인증서 번들 다운로드
RUN curl --fail --show-error --silent \
    https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem \
    -o /tmp/rds-bundle.pem

# PEM 번들에서 서울 리전 RSA2048 G1 루트 CA만 선택해 등록
RUN awk '/-----BEGIN CERTIFICATE-----/ {n++} n > 0 {print > ("/tmp/rds-" n ".pem")}' \
      /tmp/rds-bundle.pem \
    && count=0 \
    && for cert in /tmp/rds-*.pem; do \
         if openssl x509 -in "$cert" -noout -subject \
             | grep -q 'Amazon RDS ap-northeast-2 Root CA RSA2048 G1'; then \
           keytool -importcert -noprompt -trustcacerts \
             -alias nyummy-rds-ca \
             -file "$cert" \
             -keystore /tmp/rds-cacerts \
             -storepass changeit \
           && count=$((count + 1)); \
         fi; \
       done \
    && test "$count" -eq 1 \
    && keytool -list -keystore /tmp/rds-cacerts \
         -storepass changeit -alias nyummy-rds-ca


# 3단계: 실행
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN adduser --system --group spring

# 프로파일 수집(Pyroscope Java agent). app.jar보다 먼저 받아 레이어 캐시를 살린다.
# 버전을 올리면 checksum도 같이 바꾼다.
ADD --checksum=sha256:ad738199a90734f7b8392bae1ab892027d411acb953ef1f00cd6e8e7c669ff8f \
    https://github.com/grafana/pyroscope-java/releases/download/v2.9.2/pyroscope.jar \
    /app/pyroscope.jar

# agent는 항상 붙이고 켜기만 환경변수로 한다. 기본 꺼짐(local compose).
# dev/prod는 PYROSCOPE_AGENT_ENABLED=true와 서버 주소·인증 정보를 준다.
# agent가 async-profiler 네이티브 라이브러리를 로드하므로 ENTRYPOINT에 --enable-native-access를 준다
# (Java 25는 경고만, 이후 버전은 차단 예정).
ENV PYROSCOPE_AGENT_ENABLED=false \
    PYROSCOPE_APPLICATION_NAME=nyummy

COPY --from=build /app/build/libs/app.jar app.jar

# 어드민 웹 빌드 결과(AdminProperties.webLocation 기본값 file:/app/admin-web/)
COPY --from=admin-web /admin-web/dist /app/admin-web

# 기존 Java CA 목록을 보존하면서 RDS CA를 추가한 저장소
COPY --from=certs /tmp/rds-cacerts /app/rds-cacerts

RUN chown -R spring:spring app.jar rds-cacerts pyroscope.jar admin-web

USER spring

EXPOSE 8080

ENTRYPOINT ["java", \
    "-javaagent:/app/pyroscope.jar", \
    "--enable-native-access=ALL-UNNAMED", \
    "-Djavax.net.ssl.trustStore=/app/rds-cacerts", \
    "-Djavax.net.ssl.trustStorePassword=changeit", \
    "-jar", "app.jar"]