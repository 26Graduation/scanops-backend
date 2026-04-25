# ── 1. Build stage ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Gradle wrapper & dependency cache layer
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Source copy & build (tests skipped for faster CI)
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

# ── 2. Runtime stage ────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
