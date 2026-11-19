# PAGE 2 – ARCHITECTURE, DATABASE MIGRATION & OTP SPECIFICATION

**Project**: PIT Messenger  
**Date**: 2026-11-18  
**Phase**: Page 2 – Architectural Decoupling, Storage Migration & Verification Engine  
**Status**: Completed  

---

## 1. Summary of Architectural Achievements

During Page 2, the core decoupled architecture for PIT Messenger has been formalized into executable schemas and service contracts:

1. **Relational MySQL 8.x / MariaDB Schema (Verified & Live)**:
   - Designed and published at `database-migration/schema/01_init_pit_messenger_schema.sql`.
   - Successfully initialized on Oracle MySQL Server 8.0.46 running on `127.0.0.1:3306`.
   - Dedicated application user `pituser` (`pitpassword!`) provisioned with full privileges on `pit_messenger.*`.
   - Created all 11 core tables: `accounts`, `devices`, `ec_one_time_prekeys`, `signed_prekeys`, `paged_pq_prekeys`, `persistent_messages`, `profiles_v1`, `profiles_v2`, `verification_sessions`, `phone_number_identifiers`, and `used_link_device_tokens`.
2. **Storage Migration & Concurrency Design**:
   - Detailed specification published at `database-migration/README.md`.
   - Concurrency controlled via optimistic locking (`version BIGINT UNSIGNED`).
   - One-time prekeys protected from race conditions through row-level locking (`FOR UPDATE SKIP LOCKED`).
   - Offline persistent envelopes indexed by expiration for automated background TTL sweeps.
3. **Custom Registration & OTP Engine (Verified & Functional)**:
   - Implemented in `custom-backend/` (`server.js`, `start-backend.ps1`, `test-otp-flow.ps1`).
   - Validated end-to-end against live MySQL:
     - `POST /v1/registration/request-code` -> generates salted 6-digit OTP and creates session record.
     - `POST /v1/registration/verify-code` -> validates hash and issues single-use verification token `tok_v1_...`.
     - `GET /v1/health` -> confirms healthy status and active database connection pool.
4. **Local Infrastructure Orchestration**:
   - Docker Compose stack published at `deployment/docker-compose.yml`.
   - Containerizes Redis 7.4, MinIO (S3 replacement for encrypted attachments), and MySQL 8.0.
   - Volumes anchored strictly on drive `D:\` (`D:\signalappppp\deployment\data\`) to preserve drive `C:\` space.

---

## 2. Updated Project Structure

```
D:\signalappppp\ (and C:\PIT-Messenger\)
│
├── signal-android/                 # Android Client (branch: pit-messenger-custom)
│
├── signal-server/                  # Backend Server (branch: pit-messenger-custom)
│
├── registration-service/           # Reserved for standalone registration microservice
│
├── custom-backend/
│   └── README.md                   # Custom OTP API contract & security architecture
│
├── database-migration/
│   ├── README.md                   # Relational mapping & concurrency guide
│   └── schema/
│       └── 01_init_pit_messenger_schema.sql # Complete MySQL 8.x schema
│
├── deployment/
│   ├── README.md                   # Container stack documentation
│   ├── docker-compose.yml          # Local Redis + MinIO + MySQL stack
│   └── data/                       # Volume data directories on D:
│
├── documentation/
│   ├── PROJECT-INITIALIZATION.md
│   ├── GIT-WORKFLOW.md
│   ├── DEVELOPMENT-ENVIRONMENT.md
│   ├── ANDROID-BUILD-REPORT.md
│   ├── SERVER-BUILD-REPORT.md
│   ├── INITIAL-DEPENDENCY-REPORT.md
│   └── PAGE-2-ARCHITECTURE-AND-DATABASE-MIGRATION-PLAN.md
│
└── PROJECT-ARCHITECTURE.md
```

---

## 3. Strict Restrictions Compliance Check

During Page 2:
- [x] FoundationDB and DynamoDB in original source trees remain untouched on pristine default branches.
- [x] Client-side SQLCipher and Double Ratchet encryption are strictly preserved.
- [x] Zero plaintexts touch the database layer.
- [x] No changes have been pushed to GitHub.
- [x] All development remains strictly local.
- [x] All storage and volume mounts remain strictly on drive `D:\`.
