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
# NFR-012: the fictitious seed is for the local profile only and must not ship in the image
RUN jar tf /app/app.jar > /tmp/jar-entries \
    && if grep -q 'db/seed/' /tmp/jar-entries; then echo 'db/seed must not ship in the image' >&2; exit 1; fi \
    && rm /tmp/jar-entries

# 3. Runtime: JRE only, no build tools, a user without privileges
FROM eclipse-temurin:25-jre
# TEMPORARY: Trivy fails the build on the HIGH CVE-2026-109518 in apt and libapt-pkg7.0 3.2.0 of this base image, fixed
# in 3.2.1ubuntu0.1. Remove this RUN when eclipse-temurin:25-jre ships apt 3.2.1ubuntu0.1 or newer
# (check with: docker run --rm eclipse-temurin:25-jre apt --version). The base image is not pinned by digest, so a
# rebuilt tag is picked up on its own. This stage runs as root until the USER line below; the apt lists go away in
# the same layer.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --only-upgrade apt libapt-pkg7.0 \
    && rm -rf /var/lib/apt/lists/*
# TEMPORARY: Trivy fails the build on the HIGH CVE-2026-97054 in the Go standard library (v1.27.3, fixed in 1.27.4) of
# /usr/bin/pebble, a Canonical service manager that the base image ships outside of dpkg (so there is no package to
# upgrade). The system never runs it (the ENTRYPOINT is java), so it is removed. Remove this RUN when
# eclipse-temurin:25-jre ships a pebble built with Go 1.27.4 or newer, or without pebble (check with: trivy image
# eclipse-temurin:25-jre).
RUN rm -f /usr/bin/pebble
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=backend /app/app.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
