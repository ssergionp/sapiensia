# One image with the whole system: the React build is served by the Spring Boot API.
# Build: just imagem   ·   Run in production with the prod profile and the secrets from the platform.

# 1. Frontend build
FROM node:22-bookworm-slim AS frontend
WORKDIR /app/frontend
RUN corepack enable
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

# 2. Backend build, with the frontend inside the jar (classpath:/static)
FROM eclipse-temurin:25-jdk AS backend
WORKDIR /app/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY backend/src src
COPY --from=frontend /app/frontend/dist src/main/resources/static
RUN ./mvnw -q -B -DskipTests package && cp target/*.jar /app/app.jar

# 3. Runtime: JRE only, no build tools, a user without privileges
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=backend /app/app.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
