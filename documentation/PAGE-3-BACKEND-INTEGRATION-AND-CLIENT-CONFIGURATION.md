# PAGE 3 – BACKEND INTEGRATION, STORAGE LINK & CLIENT CONFIGURATION

**Project**: PIT Messenger  
**Date**: 2026-11-18  
**Phase**: Page 3 – Storage Layer Alignment, Client Endpoints & Custom OTP Integration  
**Status**: Completed  

---

## 1. Branch Strategy & Alignment

To establish the unified architecture of PIT Messenger without disturbing upstream Signal references or uncommitted work:

1. **Backend Server (`signal-server/`)**:
   - Branch: `pit-messenger-custom`
   - Action: Fast-forward merged `mysql-storage` onto `pit-messenger-custom`.
   - Result: Incorporates the full Java/Dropwizard MySQL storage layer (`MySqlAccounts`, `MySqlMessagesStore`, `MySqlSingleUseECPreKeyStore` using `SKIP LOCKED`, HikariCP, and Flyway migrations V1–V4).
   - Reference Branch: `main` remains untouched at `21be84230390a80bbf33a016a12a8ef4ab9863e7`.

2. **Android Client (`signal-android/`)**:
   - Branch: `pit-messenger-custom`
   - Action: Fast-forward merged `feature/securechat-privacy-group-media` onto `pit-messenger-custom`.
   - Result: Incorporates local cleartext network configuration, memory-tuned Gradle builds for 8 GB RAM, and privacy safeguards.
   - Reference Branch: `main` (`7c1a497356`) and `upstream/main` (`d507a800a6`) remain untouched.

---

## 2. Storage Layer & Database Connectivity

| Setting | Value | Verification |
| :--- | :--- | :--- |
| **Engine** | Oracle MySQL Server 8.0.46 | Active on port `3306` (PID `6968`) |
| **Database** | `pit_messenger` | 11 relational tables initialized |
| **Application User** | `pituser` | Granted full access on `pit_messenger.*` |
| **Application Password** | `pitpassword!` | Verified via native client |
| **Server Config** | `signal-server/config/pit_messenger.local.env` | Configured with `SIGNAL_DB_*` environment parameters |

---

## 3. Client Endpoint Configuration

- **Target IP**: `10.141.154.91` (Verified matching active host Wi-Fi interface).
- **Backend Port**: `8080` (Dropwizard / local bridge).
- **Custom OTP Port**: `8088` (`custom-backend`).
- **Network Security Policy**: Configured in `app/src/main/res/xml/network_security_config.xml` permitting cleartext communication with `10.141.154.91`, `127.0.0.1`, and emulator `10.0.2.2`.

---

## 4. Custom OTP & Verification Flow Verification

- Microservice location: `custom-backend/`
- Verification engine:
  - `POST /v1/registration/request-code` generates dynamic cryptographically salted 6-digit codes.
  - `POST /v1/registration/verify-code` validates hashes against `verification_sessions` in MySQL and issues `tok_v1_...` single-use tokens.
- Test runner: `custom-backend/test-otp-flow.ps1` executes end-to-end verification and confirms row creation in `pit_messenger.verification_sessions`.

---

## 5. Summary Checklist

- [x] Both repositories aligned on `pit-messenger-custom`.
- [x] Original default branches protected.
- [x] Zero GitHub pushes executed.
- [x] MySQL 8 database active and provisioned.
- [x] Client endpoints aligned with host LAN IP (`10.141.154.91`).
- [x] Custom OTP service verified and integrated with database.
