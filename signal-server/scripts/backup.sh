#!/usr/bin/env bash
# ==============================================================================
# Automated Backup Script for Signal-Server (MySQL + MinIO Attachments + Config)
# ==============================================================================
# Usage:
#   sudo bash scripts/backup.sh
# Can be scheduled via cron:
#   0 2 * * * /opt/signal-server/scripts/backup.sh >> /var/log/signal-backup.log 2>&1
# ==============================================================================

set -euo pipefail

BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/signal-server}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_DIR="${BACKUP_ROOT}/${TIMESTAMP}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"

DB_HOST="${SIGNAL_DB_HOST:-127.0.0.1}"
DB_PORT="${SIGNAL_DB_PORT:-3306}"
DB_USER="${SIGNAL_DB_USER:-signal}"
DB_PASSWORD="${SIGNAL_DB_PASSWORD:-signal_db_secret_pass}"
DB_NAME="${SIGNAL_DB_NAME:-signal}"

MINIO_DATA_DIR="${MINIO_DATA_DIR:-/var/lib/minio}"
CONFIG_DIR="${CONFIG_DIR:-/opt/signal-server/config}"

echo "=============================================================================="
echo "Starting Signal-Server Backup: ${TIMESTAMP}"
echo "Target directory: ${BACKUP_DIR}"
echo "=============================================================================="

mkdir -p "${BACKUP_DIR}"

# 1. Backup MySQL Database
echo "[1/3] Dumping MySQL database '${DB_NAME}'..."
MYSQL_PWD="${DB_PASSWORD}" mysqldump \
    -h "${DB_HOST}" \
    -P "${DB_PORT}" \
    -u "${DB_USER}" \
    --single-transaction \
    --quick \
    --routines \
    --triggers \
    "${DB_NAME}" | gzip -9 > "${BACKUP_DIR}/database_${DB_NAME}_${TIMESTAMP}.sql.gz"

echo "Database dump created: $(ls -lh "${BACKUP_DIR}/database_${DB_NAME}_${TIMESTAMP}.sql.gz" | awk '{print $5}')"

# 2. Backup MinIO Attachments
echo "[2/3] Backing up MinIO attachment storage..."
if [ -d "${MINIO_DATA_DIR}" ]; then
    tar -czf "${BACKUP_DIR}/minio_data_${TIMESTAMP}.tar.gz" -C "$(dirname "${MINIO_DATA_DIR}")" "$(basename "${MINIO_DATA_DIR}")"
    echo "MinIO archive created: $(ls -lh "${BACKUP_DIR}/minio_data_${TIMESTAMP}.tar.gz" | awk '{print $5}')"
else
    echo "Notice: MinIO data dir ${MINIO_DATA_DIR} not found. Skipping MinIO filesystem backup."
fi

# 3. Backup Configuration & Cryptographic Keys
echo "[3/3] Backing up server configuration and keys..."
if [ -d "${CONFIG_DIR}" ]; then
    tar -czf "${BACKUP_DIR}/config_${TIMESTAMP}.tar.gz" -C "$(dirname "${CONFIG_DIR}")" "$(basename "${CONFIG_DIR}")"
fi

if [ -f "/opt/signal-server/.env" ]; then
    cp "/opt/signal-server/.env" "${BACKUP_DIR}/env_${TIMESTAMP}.bak"
fi

# 4. Prune Old Backups
echo "Cleaning up backups older than ${RETENTION_DAYS} days..."
find "${BACKUP_ROOT}" -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" -exec rm -rf {} +

echo "=============================================================================="
echo "Backup Completed Successfully at $(date)"
echo "Backup location: ${BACKUP_DIR}"
echo "=============================================================================="
