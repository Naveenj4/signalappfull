#!/usr/bin/env bash
# ==============================================================================
# MinIO S3 Setup & Bucket Lifecycle Script
# Creates required buckets and retention rules for Signal Server CDN
# ==============================================================================

set -euo pipefail

MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://127.0.0.1:9000}"
MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-minioadmin}"
MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-minio_strong_password}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"

echo "--> Configuring MinIO client (mc)..."
if ! command -v mc &> /dev/null; then
    echo "Downloading MinIO client 'mc'..."
    curl -sS -O https://dl.min.io/client/mc/release/linux-amd64/mc
    chmod +x mc
    sudo mv mc /usr/local/bin/
fi

mc alias set local "$MINIO_ENDPOINT" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY"

echo "--> Creating required S3 buckets for Signal-Server..."
for BUCKET in cdn attachments profiles backups prekeys; do
    if mc ls "local/$BUCKET" &> /dev/null; then
        echo "  Bucket '$BUCKET' already exists."
    else
        echo "  Creating bucket '$BUCKET'..."
        mc mb "local/$BUCKET"
    fi
done

echo "--> Setting download permissions on public CDN buckets..."
mc anonymous set download local/cdn 2>/dev/null || true
mc anonymous set download local/profiles 2>/dev/null || true

echo "--> Configuring attachment lifecycle expiration (${RETENTION_DAYS} days)..."
mc ilm rule add --expire-days "$RETENTION_DAYS" local/attachments 2>/dev/null || true
mc ilm rule add --expire-days "$RETENTION_DAYS" local/cdn 2>/dev/null || true

echo "--> MinIO buckets and lifecycle configuration complete."
