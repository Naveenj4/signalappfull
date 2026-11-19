# ==============================================================================
# Multi-Stage Dockerfile for Signal-Server (MySQL Edition)
# ==============================================================================

# Stage 1: Build fat JAR using Eclipse Temurin JDK 22
FROM eclipse-temurin:22-jdk-jammy AS builder

WORKDIR /build

# Copy Maven wrapper and POMs
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY service/pom.xml service/
COPY api-doc/pom.xml api-doc/

# Cache Maven dependencies
RUN ./mvnw dependency:go-offline -B -DskipTests || true

# Copy source code
COPY service/src service/src
COPY api-doc/src api-doc/src

# Build production JAR (shaded fat jar)
RUN ./mvnw clean package -B -Dmaven.test.skip=true -Djava.release.version=22 -pl service -am

# Stage 2: Minimal Production JRE Runtime
FROM eclipse-temurin:22-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    netcat \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy compiled JAR from builder stage
COPY --from=builder /build/service/target/service-*.jar /app/signal-server.jar

# Expose HTTP application and Dropwizard admin ports
EXPOSE 8080 8081

# Default environment variables
ENV SIGNAL_DB_HOST=mysql \
    SIGNAL_DB_PORT=3306 \
    SIGNAL_DB_NAME=signal \
    SIGNAL_DB_USER=signal \
    SIGNAL_DB_PASSWORD=signal_db_secret_pass \
    SECRETS_BUNDLE_FILE=/app/config/secrets.bundle.yml \
    CONFIG_FILE=/app/config/production.yml

# Health check using Dropwizard admin ping
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://127.0.0.1:8081/ping || exit 1

ENTRYPOINT ["sh", "-c", "exec java -Xmx2g -Xms512m -Dsecrets.bundle.filename=${SECRETS_BUNDLE_FILE} -jar /app/signal-server.jar server ${CONFIG_FILE}"]
