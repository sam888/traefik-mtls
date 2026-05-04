# Stage 1: Build the application using Gradle 9.3.1
# Note: Gradle 9.x requires JDK 17+ to run the daemon
FROM gradle:9.3.1-jdk17 AS builder
WORKDIR /app

# Copy only the necessary files for dependency resolution first (better build cache)
COPY build.gradle settings.gradle /app/
COPY src /app/src

# Build the Spring Boot "Fat Jar"
# We use --no-daemon here because Docker containers are short-lived
RUN gradle bootJar --no-daemon -x test

# ---- Runtime image ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/api-1.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
