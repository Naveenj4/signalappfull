# Self-Hosted Signal Server Deployment Guide (MySQL / MariaDB Edition)

This guide walks you through deploying the self-hosted Signal Server on a Linux Virtual Private Server (Ubuntu 22.04 LTS or 24.04 LTS).

---

## 1. System Requirements

For a company deployment of up to 200 users:
- **Operating System:** Ubuntu 22.04 LTS or 24.04 LTS (x86_64 or aarch64)
- **RAM:** Minimum 4 GB (8 GB recommended for compiling and running services concurrently)
- **CPU:** 2 vCPUs minimum (4 vCPUs recommended)
- **Storage:** 40 GB SSD or NVMe
- **Domain:** A fully-qualified domain name (e.g. `signal.yourcompany.internal` or `signal.yourcompany.com`) pointing to your VPS IP with ports 80, 443, 3478 (TURN), and 5349 (TURNS) open.

---

## 2. Architecture Overview

```
                      +-----------------------------+
                      | Signal-Android / Desktop    |
                      +--------------+--------------+
                                     | HTTPS / WSS
                                     v
                      +-----------------------------+
                      |   Nginx Reverse Proxy       |
                      |   (TLS, WebSockets, Rate)   |
                      +--------------+--------------+
                                     | HTTP :8080
                                     v
                      +-----------------------------+
                      |   Signal-Server (Dropwizard)|
                      +-------+-------------+-------+
                              |             |
           +------------------+             +-----------------+
           |                                                  |
           v                                                  v
+-----------------------+                         +-----------------------+
|  MySQL 8.0+ / MariaDB |                         |      Redis 7+         |
|  InnoDB, utf8mb4      |                         |  (Cache & Queues)     |
+-----------------------+                         +-----------------------+
           |
           v
+-----------------------+
|  MinIO (S3 Storage)   |
|  (Media & Attachments)|
+-----------------------+
```

---

## 3. Deployment Options

You can deploy the stack using either **Native Installation (Systemd)** or **Docker Compose**.

### Option A: Native Ubuntu VPS Installation (Recommended for Performance)

#### Step 1: Run System Package Installer
Run the automated installation script which installs JDK 22, Maven, MariaDB/MySQL, Redis, MinIO, Coturn, Nginx, and Certbot:
```bash
sudo bash scripts/install-vps.sh
```

#### Step 2: Initialize Database
Run the database setup script to create the `signal` database, create the `signal` database user, and verify schema migrations:
```bash
bash scripts/setup-db.sh
```
*(Alternatively, if using phpMyAdmin or XAMPP, open phpMyAdmin, create a database named `signal` with collation `utf8mb4_unicode_ci`, and import `migrations/init.sql`)*.

#### Step 3: Generate Cryptographic Secrets
Generate random cryptographic keys for ZK groups, unidentified sender trust roots, link device secrets, and the admin API key:
```bash
bash scripts/generate-keys.sh
```
This script creates `config/secrets.bundle.yml` and updates `CLIENT_KEYS.md` with your server's public credentials.

#### Step 4: Configure Environment Variables
Copy `.env.example` to `.env` and verify your settings:
```bash
cp .env.example .env
nano .env
```
Ensure `SIGNAL_DB_PASSWORD` matches the password configured in Step 2.

#### Step 5: Build and Deploy the Server
Compile the Java application and place the fat JAR in `/opt/signal-server/`:
```bash
bash scripts/build.sh
```

#### Step 6: Configure Systemd and Start Services
Enable and start the `signal-server` service:
```bash
sudo cp systemd/signal-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now signal-server
sudo systemctl status signal-server
```

#### Step 7: Configure Nginx & TLS
Edit `/etc/nginx/sites-available/signal.conf` with your actual domain name:
```bash
sudo cp nginx/signal.conf /etc/nginx/sites-available/signal.conf
sudo nano /etc/nginx/sites-available/signal.conf
# Replace signal.yourcompany.internal with your actual domain

sudo ln -sf /etc/nginx/sites-available/signal.conf /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
```

Obtain a free Let's Encrypt TLS certificate:
```bash
sudo certbot --nginx -d signal.yourcompany.com
sudo nginx -t
sudo systemctl reload nginx
```

---

### Option B: Docker Compose Deployment

If you prefer running everything inside isolated Docker containers:

1. Install Docker and Docker Compose on your Ubuntu VPS:
   ```bash
   curl -fsSL https://get.docker.com | sh
   sudo usermod -aG docker $USER
   ```
2. Generate keys:
   ```bash
   bash scripts/generate-keys.sh
   ```
3. Start the entire stack with one command:
   ```bash
   docker compose up -d --build
   ```
4. Verify running containers:
   ```bash
   docker compose ps
   ```

---

## 4. Verification and Smoke Testing

Run the automated smoke test script to verify end-to-end functionality:
```bash
bash scripts/smoke-test.sh
```
The test verifies:
- Dropwizard Admin and Health Check endpoints
- Admin API authorization and allowlist security
- Staff member authorization and fixed-code registration
- Outsider / non-allowlisted phone number rejection (HTTP 403)
- Dynamic one-time verification code generation

---

## 5. Adding Staff Members

To allow a company employee to register on your private Signal server:

### Method 1: Using the helper script
```bash
bash scripts/add-user.sh "+15551234567" "Alice Smith - Engineering"
```

### Method 2: Using the Admin REST API
```bash
curl -X POST https://signal.yourcompany.com/v1/admin/registrations \
  -H "X-Admin-Api-Key: your_admin_api_key_here" \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber": "+15551234567", "note": "Alice Smith"}'
```

### Method 3: Direct SQL via phpMyAdmin or MySQL CLI
```sql
INSERT INTO allowed_registrations (phone_number, note) 
VALUES ('+15551234567', 'Alice Smith') 
ON DUPLICATE KEY UPDATE note = VALUES(note);
```

When Alice opens the modified Signal client and enters `+15551234567`, she can complete registration using the configured verification code (`123456` or the one-time code generated via admin API).

---

## 6. Daily Maintenance & Automated Backups

To set up automatic daily backups of MySQL data, MinIO attachments, and server configs:

```bash
sudo chmod +x /opt/signal-server/scripts/backup.sh
sudo ln -sf /opt/signal-server/scripts/backup.sh /etc/cron.daily/signal-backup
```
Backups are compressed with gzip and stored in `/var/backups/signal-server/` with automatic 7-day retention cleanup.

---

## 7. Troubleshooting

### Checking Server Logs
```bash
sudo journalctl -u signal-server -f -n 100
```

### Checking MySQL Connectivity
```bash
mysql -h 127.0.0.1 -u signal -p signal -e "SHOW TABLES; SELECT COUNT(*) FROM accounts;"
```

### Restarting Services
```bash
sudo systemctl restart signal-server
sudo systemctl restart nginx
sudo systemctl restart mariadb # or mysql
sudo systemctl restart redis-server
```
