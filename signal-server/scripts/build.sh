#!/usr/bin/env bash
# ==============================================================================
# Signal Server Build Script for Linux VPS
# Compiles the server jar using Maven wrapper and deploys to /opt/signal-server
# ==============================================================================

set -euo pipefail

echo "=========================================================="
echo " Building Signal-Server (MySQL/MariaDB Edition)"
echo "=========================================================="

# Check Java version
if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed. Please run ./scripts/install-vps.sh first."
    exit 1
fi

java -version

# Ensure Maven wrapper is executable
chmod +x ./mvnw 2>/dev/null || true

echo "--> Running Maven build (skipping unit tests)..."
if [ -f "./mvnw" ]; then
    ./mvnw clean package -Dmaven.test.skip=true -pl service -am
elif command -v mvn &> /dev/null; then
    mvn clean package -Dmaven.test.skip=true -pl service -am
else
    echo "Error: Neither ./mvnw nor mvn found on PATH."
    exit 1
fi

# Locate output jar
JAR_FILE=$(find service/target -name "service-*.jar" ! -name "original-*" ! -name "*-sources.jar" | head -n 1)

if [ -z "$JAR_FILE" ] || [ ! -f "$JAR_FILE" ]; then
    echo "Error: Build finished but could not locate service-*.jar in service/target!"
    exit 1
fi

echo "--> Build succeeded: $JAR_FILE"

# Deploy to /opt/signal-server if running with write permissions
TARGET_DIR="/opt/signal-server"
if [ -d "$TARGET_DIR" ] && [ -w "$TARGET_DIR" ]; then
    echo "--> Deploying jar to $TARGET_DIR/whisper-server.jar..."
    cp "$JAR_FILE" "$TARGET_DIR/whisper-server.jar"
    cp config/secrets.bundle.yml "$TARGET_DIR/secrets.bundle.yml" 2>/dev/null || true
    echo "--> Deployed successfully to $TARGET_DIR/whisper-server.jar"
fi

echo "=========================================================="
echo " Signal Server build complete!"
echo "=========================================================="
