#!/usr/bin/env bash
# ==============================================================================
# Signal Server VPS Native Installation Script
# Supported OS: Ubuntu 22.04 LTS / Ubuntu 24.04 LTS
# Installs: JDK 22+ (Temurin), Maven 3.9, MariaDB/MySQL, Redis Cluster (single-node),
#           MinIO S3, coturn (TURN), Nginx, Certbot
# ==============================================================================

set -euo pipefail

if [ "$EUID" -ne 0 ]; then
  echo "Error: This script must be run as root (sudo ./scripts/install-vps.sh)"
  exit 1
fi

echo "=========================================================="
echo " Starting Signal-Server VPS Native Installation"
echo "=========================================================="

export DEBIAN_FRONTEND=noninteractive

# 1. Update system packages
echo "--> Updating APT packages..."
apt-get update && apt-get upgrade -y
apt-get install -y curl wget gnupg lsb-release jq unzip build-essential ufw git redis-server coturn nginx certbot python3-certbot-nginx

# 2. Install Eclipse Temurin JDK (Java 22+)
echo "--> Installing Eclipse Temurin JDK..."
mkdir -p -m 755 /etc/apt/keyrings
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | tee /etc/apt/keyrings/adoptium.asc > /dev/null
echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | tee /etc/apt/sources.list.d/adoptium.list
apt-get update

# Install Temurin 22 (or fallback to 21/latest available)
if apt-cache show temurin-22-jdk >/dev/null 2>&1; then
    apt-get install -y temurin-22-jdk
elif apt-cache show temurin-23-jdk >/dev/null 2>&1; then
    apt-get install -y temurin-23-jdk
else
    apt-get install -y temurin-21-jdk openjdk-21-jdk
fi

# Verify Java
java -version

# 3. Install Maven 3.9
echo "--> Installing Apache Maven 3.9..."
MAVEN_VER="3.9.9"
wget -q "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VER}/binaries/apache-maven-${MAVEN_VER}-bin.tar.gz" -O /tmp/maven.tar.gz
tar -xzf /tmp/maven.tar.gz -C /opt/
ln -sfn "/opt/apache-maven-${MAVEN_VER}" /opt/maven
ln -sfn /opt/maven/bin/mvn /usr/local/bin/mvn
rm /tmp/maven.tar.gz

# 4. Install & Configure MariaDB Server (or MySQL 8.0)
echo "--> Installing MariaDB Server..."
apt-get install -y mariadb-server mariadb-client
systemctl enable mariadb
systemctl start mariadb

# 5. Configure Redis in Single-Node Cluster Mode
echo "--> Configuring Redis in Single-Node Cluster Mode for Signal..."
cat << 'EOF' > /etc/redis/redis.conf
bind 127.0.0.1 ::1
protected-mode yes
port 6379
tcp-backlog 511
timeout 0
tcp-keepalive 300
daemonize yes
supervised systemd
pidfile /run/redis/redis-server.pid
loglevel notice
logfile /var/log/redis/redis-server.log
databases 16
always-show-logo no
save 900 1
save 300 10
save 60 10000
stop-writes-on-bgsave-error yes
rdbcompression yes
rdbchecksum yes
dbfilename dump.rdb
dir /var/lib/redis
cluster-enabled yes
cluster-config-file nodes-6379.conf
cluster-node-timeout 5000
appendonly yes
appendfilename "appendonly.aof"
appendfsync everysec
EOF

systemctl restart redis-server
systemctl enable redis-server

# Assign all 16384 cluster hash slots to 127.0.0.1:6379
echo "--> Initializing Redis Cluster Slots (0-16383)..."
sleep 2
redis-cli cluster addslots $(seq 0 16383) 2>/dev/null || true

# 6. Install MinIO (S3-compatible attachment/avatar storage)
echo "--> Installing MinIO Binary..."
wget -q https://dl.min.io/server/minio/release/linux-amd64/minio -O /usr/local/bin/minio
chmod +x /usr/local/bin/minio
useradd -r minio-user -s /sbin/nologin 2>/dev/null || true
mkdir -p /var/lib/minio /etc/minio
chown -R minio-user:minio-user /var/lib/minio /etc/minio

# MinIO systemd environment file
cat << 'EOF' > /etc/default/minio
MINIO_VOLUMES="/var/lib/minio"
MINIO_OPTS="--address 127.0.0.1:9000 --console-address 127.0.0.1:9001"
MINIO_ROOT_USER="minioadmin"
MINIO_ROOT_PASSWORD="minio_strong_password"
EOF

# MinIO systemd unit file
cat << 'EOF' > /etc/systemd/system/minio.service
[Unit]
Description=MinIO High Performance Object Storage
Documentation=https://docs.min.io
Wants=network-online.target
After=network-online.target

[Service]
User=minio-user
Group=minio-user
ProtectProc=invisible
EnvironmentFile=/etc/default/minio
ExecStart=/usr/local/bin/minio server $MINIO_OPTS $MINIO_VOLUMES
Restart=always
LimitNOFILE=65536
TasksMax=infinity
TimeoutStopSec=infinity
SendSIGKILL=no

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable minio
systemctl start minio

# 7. Create Signal application directory
mkdir -p /opt/signal-server /var/log/signal-server /var/run/signal-server

echo "=========================================================="
echo " System dependencies successfully installed!"
echo " Next step: run ./scripts/setup-db.sh to configure the database."
echo "=========================================================="
