#!/usr/bin/env bash
# ==============================================================================
# Signal Server Database Setup Script (MySQL / MariaDB)
# Creates database, user, permissions, and verifies schema
# ==============================================================================

set -euo pipefail

DB_NAME="${SIGNAL_DB_NAME:-signal}"
DB_USER="${SIGNAL_DB_USER:-signal}"
DB_PASS="${SIGNAL_DB_PASSWORD:-signal_db_secret_pass}"
DB_HOST="${SIGNAL_DB_HOST:-127.0.0.1}"
DB_PORT="${SIGNAL_DB_PORT:-3306}"

echo "=========================================================="
echo " Setting up MySQL/MariaDB database: ${DB_NAME}"
echo "=========================================================="

# 1. Create database and user
echo "--> Creating database and user..."
mysql -u root << EOF
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
CREATE USER IF NOT EXISTS '${DB_USER}'@'127.0.0.1' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'127.0.0.1';
FLUSH PRIVILEGES;
EOF

# 2. Check if migrations/init.sql exists and apply it
if [ -f "migrations/init.sql" ]; then
    echo "--> Applying initial schema migrations (migrations/init.sql)..."
    mysql -u "${DB_USER}" -p"${DB_PASS}" -h "${DB_HOST}" -P "${DB_PORT}" "${DB_NAME}" < migrations/init.sql
    echo "--> Initial schema successfully applied!"
fi

# 3. Verify tables
echo "--> Verifying database tables:"
mysql -u "${DB_USER}" -p"${DB_PASS}" -h "${DB_HOST}" -P "${DB_PORT}" "${DB_NAME}" -e "SHOW TABLES;"

# 4. Write/Update .env file
echo "--> Updating .env configuration..."
cat << EOF >> .env 2>/dev/null || cat << EOF > .env
SIGNAL_DB_NAME=${DB_NAME}
SIGNAL_DB_USER=${DB_USER}
SIGNAL_DB_PASSWORD=${DB_PASS}
SIGNAL_DB_HOST=${DB_HOST}
SIGNAL_DB_PORT=${DB_PORT}
EOF

echo "=========================================================="
echo " Database setup complete! Database '${DB_NAME}' is ready."
echo "=========================================================="
