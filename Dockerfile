# ── 1. Build stage ─────────────────────────────────────────────────────────
# alpine 태그는 **amd64 전용**이다(2026-08-17 docker manifest inspect 확인:
# 17-jdk-alpine/17-jre-alpine → amd64 only, 17-jdk/17-jre/-jammy → amd64,arm,arm64,…).
# Apple Silicon 등 arm64 호스트에서 빌드가 "no match for platform in manifest" 로 실패한다.
# 멀티아치를 지원하는 jammy 태그를 쓴다.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

# Gradle wrapper & dependency cache layer
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Source copy & build (tests skipped for faster CI)
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

# ── 2. Runtime stage ────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
