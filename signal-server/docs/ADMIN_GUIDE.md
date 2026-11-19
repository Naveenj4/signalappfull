# Company Administrator Guide (ADMIN_GUIDE.md)

This guide is for company IT administrators managing the private self-hosted Signal server. It covers user management, security controls, backup procedures, and daily operations.

---

## 1. Managing Staff Access (Allowlist)

To ensure privacy and security, the server enforces a company allowlist (`allowed_registrations` table). Users whose phone numbers are not in this table cannot register or connect.

### Adding an Employee

#### Option A: Using the Admin CLI tool
On the server, run:
```bash
# Basic:
bash scripts/admin-cli.sh add-user "+15551234567" "Bob Jones" "bob@company.com" "Sales Dept"

# Generate an immediate 24h one-time verification code:
bash scripts/admin-cli.sh generate-code "+15551234567"
```

#### Option B: Using the Admin REST API
Send an authenticated HTTP POST request (e.g. from an internal IT portal or curl):
```bash
curl -X POST http://127.0.0.1:8080/v1/admin/registrations \
  -H "X-Admin-Api-Key: company_admin_secret_key_change_in_production" \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "+15551234567",
    "displayName": "Bob Jones",
    "email": "bob@company.com",
    "notes": "Sales Dept"
  }'

# Generate 24h single-use OTP:
curl -X POST http://127.0.0.1:8080/v1/admin/registrations/+15551234567/generate-code \
  -H "X-Admin-Api-Key: company_admin_secret_key_change_in_production"
```

#### Option C: Direct MySQL / phpMyAdmin query
```sql
-- Add or update employee:
INSERT INTO allowed_registrations (identifier, display_name, email, verification_code, code_expires_at, attempts_remaining, enabled, added_at, notes)
VALUES ('+15551234567', 'Bob Jones', 'bob@company.com', '849201', UNIX_TIMESTAMP() + 86400, 5, TRUE, UNIX_TIMESTAMP(), 'Sales Dept')
ON DUPLICATE KEY UPDATE 
    display_name = VALUES(display_name),
    email = VALUES(email),
    verification_code = VALUES(verification_code),
    code_expires_at = VALUES(code_expires_at),
    attempts_remaining = 5,
    enabled = TRUE;
```

---

### Verification Code Modes & Employee Onboarding

The server supports three modes via `database.verificationMode`:

1. **`ADMIN_CODE` (Production Default):**
   - No external SMS or voice calls are made.
   - An administrator generates a 6-digit one-time code for the user (expires in 24 hours, max 5 attempts, single-use).
   - Once successfully used to register, the code is automatically burned.
2. **`SMTP_EMAIL`:**
   - Sends the 6-digit OTP to the employee's work email stored in `allowed_registrations.email`.
3. **`FIXED_CODE` (Testing Only):**
   - Accepts a static test code (e.g. `123456`). Only active if `fixedVerificationCodeEnabled: true`.

---

### Admin CLI Commands Reference

```bash
# List all users:
bash scripts/admin-cli.sh list-users

# Add user:
bash scripts/admin-cli.sh add-user "+15551234567" "Bob Jones" "bob@company.com"

# Generate 24h OTP:
bash scripts/admin-cli.sh generate-code "+15551234567"

# View active OTP:
bash scripts/admin-cli.sh get-code "+15551234567"

# Temporarily disable a user (cannot register or authenticate):
bash scripts/admin-cli.sh disable-user "+15551234567"

# Re-enable user:
bash scripts/admin-cli.sh enable-user "+15551234567"

# Remove user completely:
bash scripts/admin-cli.sh remove-user "+15551234567"
```

---

### Offboarding / Revoking an Employee

When an employee leaves the company:

1. **Remove from Allowlist:**
   ```bash
   curl -X DELETE http://127.0.0.1:8080/v1/admin/registrations/+15551234567 \
     -H "X-Admin-Api-Key: company_admin_secret_key_change_in_production"
   ```
2. **Deactivate Account in Database:**
   ```sql
   DELETE FROM accounts WHERE phone_number = '+15551234567';
   ```
   The user's devices will immediately be logged out and unable to re-register.

---

## 2. Server Monitoring & Maintenance

### Viewing Server Logs
```bash
# Follow real-time server output:
sudo journalctl -u signal-server -f

# Check for warnings or errors:
sudo journalctl -u signal-server -p err..warn -n 50
```

### Checking Server Health
```bash
# Dropwizard admin healthcheck:
curl -i http://127.0.0.1:8081/healthcheck

# Quick ping:
curl http://127.0.0.1:8081/ping
# Returns: pong
```

### Checking Database Statistics
Open phpMyAdmin or run in MySQL CLI:
```sql
USE signal;

-- Count registered users:
SELECT COUNT(*) AS total_staff_accounts FROM accounts;

-- View recent registrations:
SELECT phone_number, registered_at, note FROM allowed_registrations ORDER BY registered_at DESC LIMIT 10;

-- Check pending messages in queue:
SELECT COUNT(*) AS queued_messages FROM persistent_messages;
```

---

## 3. Backup and Disaster Recovery

### Manual Backup
Run the backup script at any time:
```bash
sudo bash /opt/signal-server/scripts/backup.sh
```
This generates:
- Gzipped MySQL database dump (`database_signal_YYYYMMDD_HHMMSS.sql.gz`)
- MinIO attachments archive (`minio_data_YYYYMMDD_HHMMSS.tar.gz`)
- Configuration and cryptographic keys archive (`config_YYYYMMDD_HHMMSS.tar.gz`)

### Restoring from Backup
To restore the MySQL database on a fresh server:
```bash
gunzip -c /var/backups/signal-server/YYYYMMDD_HHMMSS/database_signal_*.sql.gz | mysql -u signal -p signal
```

To restore MinIO attachments:
```bash
tar -xzf /var/backups/signal-server/YYYYMMDD_HHMMSS/minio_data_*.tar.gz -C /var/lib/
sudo chown -R minio:minio /var/lib/minio
```

---

## 4. Security Hardening Checklist

1. **Firewall (UFW):**
   ```bash
   sudo ufw default deny incoming
   sudo ufw default allow outgoing
   sudo ufw allow 22/tcp    # SSH
   sudo ufw allow 80/tcp    # HTTP (Let's Encrypt renewal)
   sudo ufw allow 443/tcp   # HTTPS (Signal clients)
   sudo ufw allow 3478/tcp  # Coturn TURN TCP
   sudo ufw allow 3478/udp  # Coturn TURN UDP
   sudo ufw allow 5349/tcp  # Coturn TURNS TLS
   sudo ufw allow 5349/udp  # Coturn TURNS TLS
   sudo ufw enable
   ```
   *Do NOT expose ports 3306 (MySQL), 6379/7000 (Redis), 8080 (backend), 8081 (admin), or 9000 (MinIO) to the public internet.*

2. **Restrict Admin API in Nginx:**
   In `/etc/nginx/sites-available/signal.conf`, keep `location /v1/admin/` restricted to `127.0.0.1` and your internal VPN IP addresses only.

3. **Rotate Secrets:**
   Keep `.env` and `config/secrets.bundle.yml` file permissions set to `chmod 600`.
