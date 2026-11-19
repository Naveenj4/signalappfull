# Connection & Deployment Checklist (CONNECTION_CHECKLIST.md)

Follow this verification checklist in exact order on your VPS and on client mobile devices to confirm full production operation.

---

## Part 1: Server & VPS Verification

### Step (a): Verify All Services Are Running
On the VPS, check that all 6 backend services are active:
```bash
sudo systemctl status mariadb --no-pager
sudo systemctl status redis-server --no-pager
sudo systemctl status minio --no-pager
sudo systemctl status coturn --no-pager
sudo systemctl status nginx --no-pager
sudo systemctl status signal-server --no-pager
```
*What to check if it fails:*
- MariaDB: `sudo journalctl -u mariadb -n 30`
- Redis: `redis-cli ping` (should return `PONG`)
- Signal Server: `sudo journalctl -u signal-server -n 50 --no-pager`

---

### Step (b): Check Server Health Locally
Probe Dropwizard administrative and ping endpoints on localhost:
```bash
# 1. Ping endpoint
curl -s http://127.0.0.1:8081/ping
# Expected: pong

# 2. Dropwizard Healthcheck
curl -s http://127.0.0.1:8081/healthcheck | jq .
# Expected: {"deadlocks":{"healthy":true}}
```
*What to check if it fails:*
- Check database connectivity in `sudo journalctl -u signal-server | grep -E "Hikari|Flyway|MySQL"`

---

### Step (c): Verify External TLS & Public Reachability
From an outside computer or your laptop (NOT the VPS):
```bash
# 1. API endpoint
curl -I https://api.yourdomain.com/ping
# Expected: HTTP/2 200 OK

# 2. CDN endpoint (MinIO proxy)
curl -I https://cdn.yourdomain.com/minio/health/live
# Expected: HTTP/2 200 OK

# 3. Check TLS certificate validity
curl -vI https://api.yourdomain.com 2>&1 | grep -E "expire date|common name|SSL certificate"
```
*What to check if it fails:*
- Nginx configuration: `sudo nginx -t`
- Certbot certificate renewal: `sudo certbot certificates`
- VPS Firewall: `sudo ufw status` (ensure ports 80 and 443 are ALLOWED)

---

### Step (d): Add Staff Phone Number to Allowlist
On the VPS, add the employee phone number using the Admin CLI tool:
```bash
bash scripts/admin-cli.sh add-user "+15551234567" "Alice" "alice@company.com" "Engineering"
```
*Verify in database:*
```bash
bash scripts/admin-cli.sh list-users
```
*What to check if it fails:*
- Ensure `X-Admin-Api-Key` matches `ADMIN_API_KEY` in `/opt/signal-server/config/production.yml`

---

### Step (e): Generate One-Time Verification Code (OTP)
Generate a fresh 24-hour single-use OTP for the user:
```bash
bash scripts/admin-cli.sh generate-code "+15551234567"
```
Output:
```json
{
  "identifier": "+15551234567",
  "verificationCode": "849201",
  "expiresInHours": 24,
  "attemptsRemaining": 5
}
```
*What to check if it fails:*
- Confirm `allowed_registrations` table exists and `phone_number` matches the exact international E.164 format (`+<country_code><number>`).

---

## Part 2: Android Client Verification

### Step (f): Complete Registration in Signal-Android APK
1. Open your modified Signal Android app.
2. Enter the phone number: `+15551234567`.
3. Click **Next / Register**.
4. When prompted for the 6-digit code, enter `849201` (from Step e).
5. The app finishes registration and loads the profile creation screen.

*What to check in server logs if registration fails:*
```bash
sudo journalctl -u signal-server -f | grep -E "Registration|Verification|accounts"
```
- If `403 Forbidden`: Phone number is not in `allowed_registrations` table.
- If `401 Unauthorized`: Submitted verification code did not match or has expired.
- If `429 Too Many Requests`: Rate limiter triggered; wait 15 minutes or reset rate limit key in Redis: `redis-cli KEYS "*rate*" | xargs redis-cli DEL`.

---

### Step (g): Send & Receive 1:1 Messages
1. Register a second test user `+15559876543` using the same process.
2. User A sends `"Hello, private server!"` to User B.
3. User B receives the message instantly with double checkmarks.

*What to check in server logs if messaging fails:*
```bash
sudo journalctl -u signal-server -f | grep -E "MessagesManager|persistent_messages|websocket"
```
- Ensure Redis pub/sub is running (`redis-cli ping`).
- Check MySQL table `persistent_messages` to see if envelopes are being written and deleted upon ACK:
  `mysql -u signal -p -e "SELECT id, destination_uuid, client_timestamp FROM signal.persistent_messages;"`

---

### Step (h): Send & Download Attachments (Images/Audio/Files)
1. In the chat, click the attachment icon and send an image or PDF.
2. Verify the image thumbnail generates and the recipient can tap to download.

*What to check if attachment upload/download fails:*
- Server logs: `sudo journalctl -u signal-server -f | grep -E "Attachment|s3|minio"`
- Nginx CDN logs: `sudo tail -f /var/log/nginx/access.log | grep cdn`
- MinIO buckets: `mc ls local/attachments`
- Ensure `client_max_body_size 120M;` is present in `/etc/nginx/sites-available/signal.conf`.

---

### Step (i): Background Push Notifications (FCM or WebSocket)
1. On User B's Android device:
   - Close the Signal app and swipe it away from recent apps (background sleep).
2. User A sends a new message to User B.
3. User B's phone vibrates/rings with an incoming message notification.
4. Tapping the notification opens the chat directly.

*What to check if push notifications do not arrive:*
- For FCM: Verify `FcmSender` logs in `sudo journalctl -u signal-server -f | grep -E "FcmSender|firebase"`.
  - Ensure `fcm.credentials` in `secrets.bundle.yml` (or `FCM_CREDENTIALS_PATH`) is valid.
  - Ensure the client's `google-services.json` matches the same Firebase project ID.
- For FOSS / WebSocket-only build:
  - Check that the phone has battery optimization disabled for Signal ("Don't optimize").
  - Check active WebSocket connections: `sudo journalctl -u signal-server -f | grep "websocket connected"`.

---

### Step (j): 1:1 Voice & Video Call Over Mobile Data
1. Turn off Wi-Fi on both phones (connect via cellular 4G/5G data).
2. User A places an audio or video call to User B.
3. User B's phone rings; answer the call.
4. Verify two-way audio and video speak clearly.

*What to check if calls connect but have no audio or fail to connect:*
- Coturn logs: `sudo tail -f /var/log/turnserver/turn.log`
- Check relay allocation: Look for `allocate request` and `session 200 OK` in coturn log.
- Firewall: Verify UDP ports `3478` and `49152:65535` are open:
  ```bash
  sudo ufw status | grep 3478
  sudo ufw status | grep 49152
  ```
