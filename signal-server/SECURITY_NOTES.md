# Security Architecture & Operations Notes

This document details the security architecture, threat model, trade-offs, and operational hardening guidelines for running a private, company-internal Signal Server (<200 users) with MySQL 8.0+ / MariaDB 10.6+.

---

## 1. End-to-End Encryption (E2EE) Guarantee

Signal's double-ratchet protocol and zero-knowledge architectures remain fully intact:
- **Zero Ciphertext Visibility:** The server stores only encrypted envelopes (`persistent_messages` table) and encrypted profile ciphertexts (`profiles_v2` table). The server has no cryptographic ability to decrypt user messages, attachments, or contact profiles.
- **Unidentified Sender:** The server continues to verify unidentified sender certificates without learning message recipients where client support permits.
- **Prekey Cryptography:** Prekeys (Curve25519 EC keys and Kyber-1024 post-quantum KEM keys) are stored in MySQL/MariaDB in binary format and handed to clients verbatim.

---

## 2. Company Registration Allowlist

In standard Signal, any phone number in the world can attempt registration through SMS aggregators. For a company messenger:
- **`registrationAllowlistOnly: true` (Default):**
  Only phone numbers pre-registered by the administrator in the `allowed_registrations` table can initiate or complete registration. Any non-allowlisted number receives an immediate rejection (`RateLimitExceededException` / HTTP 429).
- **Admin Allowlist Management:**
  Administrators can add, list, and revoke staff phone numbers using either:
  1. The REST endpoint: `POST /v1/admin/registrations` (authenticated with `X-Admin-Api-Key`).
  2. The CLI tool: `scripts/add-user.sh +15551234567 123456 "Alice Smith - IT"`.
  3. Direct SQL via phpMyAdmin or MySQL CLI (`scripts/admin-allowlist.sql`).

---

## 3. Verification Code Security Modes

Signal Server's dependency on external SMS gateways (Twilio, TeleSign, Infobip) has been replaced by `CompanyVerificationManager`:

### Mode A: Admin-Issued One-Time Verification Codes (Recommended for Production)
- When onboarding an employee, the administrator generates a temporary one-time verification code with an expiration window (e.g., 24 hours):
  ```bash
  ./scripts/add-user.sh +15551234567 849201 "Bob Jones"
  ```
- The employee receives this code securely out-of-band (in person, company email, password manager).
- Once used or expired, the code is invalid.

### Mode B: Fixed Test Code Mode (Development / Closed Testing)
- `fixedVerificationCodeEnabled: true`, `fixedVerificationCode: "123456"`.
- Any phone number on the allowlist can register using this code.
- **Production Recommendation:** Set `fixedVerificationCodeEnabled: false` in `config/production.yml` once initial onboarding is complete.

---

## 4. Attachment Upload Restriction (`attachmentUploadAllowlist`)

- **Configuration:** `database.attachmentUploadAllowlist` (boolean, default: `false`).
- **Behavior when `true`:**
  Before `AttachmentControllerV4` issues a signed upload form for attachment uploads to MinIO/S3, it verifies that the authenticated user (`AuthenticatedDevice`) belongs to an active, allowlisted staff account in `allowed_registrations`.
- **Purpose:** Prevents unauthorized parties or de-provisioned staff members from consuming VPS disk space or bandwidth via file uploads.

---

## 5. Admin API Security

- The administrative endpoint `/v1/admin/registrations` is protected by `X-Admin-Api-Key`.
- **Key Storage:** Store `SIGNAL_ADMIN_KEY` as a high-entropy secret (at least 32 random alphanumeric characters) in your environment variables or `.env` file, never in version control.
- **Network Restriction:** In Nginx reverse proxy configurations, access to `/v1/admin/*` should be restricted to `127.0.0.1` or internal VPN subnets.

---

## 6. VPS Hardening Checklist

When deploying to Ubuntu 22.04/24.04:
1. **Firewall (UFW):**
   - Allow only ports `80` (HTTP for ACME challenge), `443` (HTTPS/WSS), and `22` (SSH).
   - MySQL (`3306`), Redis (`6379`), and MinIO S3 (`9000`) must **ONLY** listen on `127.0.0.1` and never be exposed to public interfaces.
2. **TLS / HTTPS:**
   - Terminate TLS via Nginx with Let's Encrypt (`certbot`) using TLS 1.3 and modern cipher suites.
   - Configure HSTS (`Strict-Transport-Security`).
3. **Database Security:**
   - Create a dedicated unprivileged user `signal` with permissions limited strictly to the `signal` database:
     ```sql
     CREATE USER 'signal'@'localhost' IDENTIFIED BY '<strong_password>';
     GRANT ALL PRIVILEGES ON signal.* TO 'signal'@'localhost';
     ```
4. **Regular Backups:**
   - Run automated daily backups via `scripts/backup.sh` to a secondary remote backup server or encrypted off-site storage.

---

## 7. Proprietary Signal Cloud Services (Stubbed & Disabled)

For this self-hosted deployment, all proprietary Signal cloud services that require remote SGX hardware or third-party subscriptions have been stubbed or disabled with zero impact on core chat, voice, and attachment functionality:

1. **Intel SGX Contact Discovery Service (CDS / Directory V2):**
   - *Original Behavior:* Clients send encrypted contact book hashes to Signal's Intel SGX enclaves to find which contacts are on Signal.
   - *Internal Replacement:* In a closed company setting (<200 users), all staff numbers are allowlisted. Signal Android and Desktop clients can discover peers directly through direct chat invitation or phone number lookups (`/v1/accounts`).
2. **Secure Value Recovery (SVR2 / SVRB):**
   - *Original Behavior:* Stores encrypted user PIN hashes in remote SGX hardware enclaves.
   - *Internal Replacement:* `StubSecureValueRecoveryClient` safely no-ops remote SVR deletion calls. User accounts and keys are fully managed on the MySQL database.
3. **Apple DeviceCheck:**
   - *Original Behavior:* Verifies iOS client authenticity via Apple's AppAttest servers and DynamoDB tables.
   - *Internal Replacement:* `StubAppleDeviceChecks` bypasses Apple device attestation and DynamoDB storage, enabling seamless registration for both iOS and Android.
4. **Third-Party Payments & Subscriptions:**
   - *Original Behavior:* Processes recurring donations via Stripe, Braintree, Google Play Billing, and Apple App Store.
   - *Internal Replacement:* Payment endpoints and processors are bypassed; the server runs free of any commercial payment gateway dependencies.

---

## 8. Detailed Security Flags & Trade-offs (Addendum)

| Feature / Stub | Implementation in This Server | Security Implication & Trade-off | Mitigating Control |
| :--- | :--- | :--- | :--- |
| **Captcha & Push Challenges** | Skipped for allowlisted numbers in `StubRegistrationServiceClient` | Captcha/push challenge tokens are satisfied locally without calling Google reCAPTCHA or Apple DeviceCheck. | Only numbers explicitly enabled in `allowed_registrations` can register; unauthorized external IPs cannot initiate spam sessions. |
| **Registration Lock (PIN)** | Local validation only, SGX SVR disabled | Cloud PIN recovery via Intel SGX enclaves is absent. If an employee forgets their PIN, the admin resets it. | Local account authentication via Curve25519 identity key remains active and uncompromised. |
| **Verification OTP Expiry & Burn** | Stored in MySQL `allowed_registrations`, single-use, max 5 attempts | Replay attack window limited to 10m (dynamic OTP) or 24h (admin code). Burned immediately upon verification. | Brute force prevented by attempt counter (`attempts_remaining <= 0` revokes code). |
| **APNs (Apple Push)** | Disabled by config (`APNSender` no-op) | Sleeping iOS devices cannot receive background push without an active Apple Developer APNs certificate. | Android devices use FCM HTTP v1; desktop and FOSS Android maintain persistent WebSockets. |
| **Group Calls (SFU)** | Calling Service stubbed | Multi-party group video calls require Signal's SFU media server. | 1:1 voice and video calls use direct peer-to-peer WebRTC mediated by self-hosted `coturn` TURN server. |

