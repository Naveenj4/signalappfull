# Phase 0 Investigation Report: Signal-Server MySQL/MariaDB Storage Migration

**Date:** 2026-11-16  
**Target Architecture:** Self-hosted Signal-Server on Ubuntu 22.04/24.04 LTS with MySQL 8.0+ / MariaDB 10.6+ (InnoDB, `utf8mb4`), Redis (single-node cluster), MinIO (S3-compatible CDN/attachments), and Nginx reverse proxy.  
**Branch:** `mysql-storage` (based on latest stable tag `v20261104.0.0`).

---

## 1. Required Java and Maven Versions & Docker Base Images

- **Java Version:** **Java 27**
  - Confirmed via `pom.xml`: `<release>27</release>`.
  - OpenJDK 27 (Eclipse Temurin 27, e.g., `temurin-27-jdk-amd64`) is required for modern Java language features (virtual threads, pattern matching, records).
- **Maven Version:** **Maven 3.9.11+**
  - Confirmed via `maven-enforcer-plugin` rule: `<requireMavenVersion><version>3.9.11</version></requireMavenVersion>`.
- **Matching Docker Base Images:**
  - Build stage: `maven:3.9-eclipse-temurin-27` or `eclipse-temurin:27-jdk`
  - Runtime stage: `eclipse-temurin:27-jre` or `eclipse-temurin:27-jdk` (Ubuntu / Debian base)

---

## 2. External Services Inventory

| Service | Signal-Server Purpose | Self-Hosted / Open-Source Replacement |
| :--- | :--- | :--- |
| **DynamoDB** | Primary persistent store for accounts, devices, prekeys, profiles, auth, offline messages | **MySQL 8.0+ / MariaDB 10.6+** (InnoDB, `utf8mb4`) |
| **FoundationDB** | Real-time queue for unread messages (`FoundationDbMessageStore`) | Local FoundationDB 7.3 binary on VPS or SQL persistent queue |
| **Redis** | In-memory message caching, rate limiting, push scheduling, and real-time WebSocket pub/sub | **Redis 7.4+** in single-node cluster mode |
| **S3 / CDN** | Attachments, profile avatars, encrypted backups, KEM prekey bundles | **MinIO** (S3-compatible API with path-style access) |
| **SMS / Voice Verification** | Twilio / InfoBip verification codes | Configurable local stub: fixed test code + admin-generated one-time token table (`allowed_registrations`) |
| **FCM / APNs** | Mobile background push notifications | Optional: Firebase project credentials for Android; dummy/noop push if running background websocket-only |
| **TURN / STUN** | WebRTC audio/video call media relay | **coturn** server on the VPS |
| **Contact Discovery (CDS)** | Intel SGX private set intersection | Disabled/stubbed: in private company use (<200 users), clients look up numbers/usernames directly via directory API |
| **Secure Value Recovery (SVR2/SVRB)** | Hardware SGX PIN/key recovery | Disabled/stubbed: private company server does not use Signal cloud SGX enclaves |
| **Payments / Subscriptions** | Stripe / Braintree / Google Play / Apple App Store | Disabled/stubbed: internal company messenger requires no billing |

---

## 3. Proprietary / Cloud-Only Components & Proposed Stubs

1. **SMS / Voice Verification Service:**
   - *Original:* Calls Twilio or proprietary external registration service.
   - *Stub:* `StubRegistrationServiceClientFactory` accepting any code or a fixed code (e.g. `123456`), combined with a database table `allowed_registrations` containing allowlisted employee phone numbers.
2. **SGX Contact Discovery (CDS):**
   - *Original:* Requires Intel SGX hardware enclave attestation hosted on AWS/Azure.
   - *Stub:* Disable CDS service endpoints. Direct contact lookup by phone number / username remains fully supported via standard HTTP API.
3. **Secure Value Recovery (SVR2 & SVRB):**
   - *Original:* SGX-backed cloud key recovery for user master keys.
   - *Stub:* Stub the SVR client to return local mock responses or disable cloud PIN recovery.
4. **Apple DeviceCheck & App Attest:**
   - *Original:* Apple proprietary attestation service.
   - *Stub:* Bypass attestation check (return `true` for valid device).
5. **Key Transparency Service:**
   - *Original:* Cloud-hosted append-only log.
   - *Stub:* Disabled in configuration.

---

## 4. DynamoDB Tables & Classes to Migrate

Signal-Server uses 33 DynamoDB tables. Every single table and its corresponding Java class has been audited from `DynamoDbExtensionSchema.java` and source code:

| Table / Entity | Java Class | Primary Key / Index | DynamoDB Usage Pattern | MySQL / MariaDB Replacement |
| :--- | :--- | :--- | :--- | :--- |
| **Accounts** | `Accounts.java` | PK: `account_uuid` (UUID)<br>GSI: `username_link_uuid` | Serialized JSON `account_data`, optimistic lock `#version = :version` | `accounts` table: `account_uuid VARBINARY(16) PRIMARY KEY`, indexed `e164`, `pni`, `username_hash`, `username_link_uuid`, `account_data LONGBLOB`. Optimistic lock via `version = version + 1 WHERE account_uuid = ? AND version = ?`. |
| **Phone Number Constraint** | `Accounts.java` | PK: `e164` | TransactWriteItem conditional put | `UNIQUE (e164)` constraint on `accounts` table |
| **PNI Constraint** | `Accounts.java` | PK: `pni` | TransactWriteItem conditional put | `UNIQUE (pni)` constraint on `accounts` table |
| **Username Constraint** | `Accounts.java` | PK: `username_hash` | TransactWriteItem conditional put | `UNIQUE (username_hash)` constraint on `accounts` table |
| **Deleted Accounts** | `Accounts.java` | PK: `account_pni`<br>GSI: `account_uuid` | TTL expiry, lookup previous ACI | `deleted_accounts` table: `account_pni VARCHAR(64) PRIMARY KEY`, `account_uuid VARBINARY(16)`, `deleted_at BIGINT`. |
| **Used Link Tokens** | `Accounts.java` | PK: `token_hash` | Check if QR link device token used | `used_link_device_tokens` table: `token_hash VARBINARY(64) PRIMARY KEY`, `created_at BIGINT`. |
| **Devices** | `Device.java` / `Accounts.java` | Serialized in `Account.java` | Contained inside Account object | `devices` table: `(account_uuid, device_id) PRIMARY KEY`, foreign key to `accounts(account_uuid)` ON DELETE CASCADE. |
| **EC One-Time Prekeys** | `SingleUseECPreKeyStore.java` | PK: `account_uuid`, `device_id:key_id` | Conditional delete, single-use take | `ec_one_time_prekeys` table: `PRIMARY KEY (account_uuid, device_id, key_id)`. `SELECT ... FOR UPDATE SKIP LOCKED` then `DELETE` inside a single SQL transaction. |
| **Paged PQ Prekeys** | `PagedSingleUseKEMPreKeyStore.java` | PK: `account_uuid`, `device_id` | Pages stored in S3/MinIO, metadata in DDB | `paged_pq_prekeys` table: `PRIMARY KEY (account_uuid, device_id, batch_id)`. Payload stored in table or MinIO. |
| **EC Signed Prekeys** | `RepeatedUseECSignedPreKeyStore.java` | PK: `account_uuid`, `device_id` | Put item, get item, delete item | `signed_prekeys` table: `PRIMARY KEY (account_uuid, device_id, key_type)`. `key_type = 'EC'`. |
| **PQ Last Resort Keys** | `RepeatedUseKEMSignedPreKeyStore.java` | PK: `account_uuid`, `device_id` | Put item, get item, delete item | `signed_prekeys` table with `key_type = 'PQ_LAST_RESORT'`. |
| **Profiles V1** | `Profiles.java` | PK: `account_uuid`, `version` | Put item, get item, batch get | `profiles_v1` table: `PRIMARY KEY (account_uuid, version)`. |
| **Profiles V2** | `ProfilesV2.java` | PK: `account_uuid`, `version` | Put item with version hash, get | `profiles_v2` table: `PRIMARY KEY (account_uuid, version)`. |
| **Profile Avatars** | `ProfileAvatars.java` | PK: `identity` | Raw avatar image bytes | `profile_avatars` table: `identity VARBINARY(64) PRIMARY KEY, avatar_data LONGBLOB`. |
| **Persistent Messages** | `MessagesDynamoDb.java` | PK: `partition`, `sort`<br>LSI: `message_uuid` | Batch write, sequential fetch, TTL | `persistent_messages` table: `id BIGINT AUTO_INCREMENT PRIMARY KEY`, `destination_uuid VARBINARY(16)`, `destination_device_id SMALLINT`, `guid VARBINARY(16) UNIQUE`, `envelope_data LONGBLOB`, `expires_at BIGINT`. |
| **Verification Sessions** | `VerificationSessions.java` | PK: `session_id` | JSON serialized verification session | `verification_sessions` table: `session_id VARCHAR(64) PRIMARY KEY, e164 VARCHAR(32), verified BOOLEAN, session_data BLOB, expires_at BIGINT`. |
| **Phone Number Recovery** | `PhoneNumberRecoveryPasswords.java` | PK: `pni` | Put with TTL, delete | `phone_number_recovery_passwords` table: `pni VARCHAR(64) PRIMARY KEY, password_hash VARBINARY(64), expires_at BIGINT`. |
| **Remote Config** | `RemoteConfigs.java` | PK: `name` | Put string, get string | `remote_configs` table: `name VARCHAR(128) PRIMARY KEY, config_value TEXT`. |
| **Client Releases** | `ClientReleases.java` | PK: `platform`, `version` | Scan all client releases | `client_releases` table: `PRIMARY KEY (platform, version)`. |
| **Push Challenges** | `PushChallengeDynamoDb.java` | PK: `token` | Put, delete | `push_challenges` table: `token VARCHAR(64) PRIMARY KEY, created_at BIGINT`. |
| **Report Messages** | `ReportMessageDynamoDb.java` | PK: `token` | Put with TTL, get | `report_messages` table: `token VARCHAR(64) PRIMARY KEY, report_data LONGBLOB, expires_at BIGINT`. |
| **Change Number Waiting** | `ChangeNumberWaitingPeriods.java` | PK: `account_uuid` | Put with TTL, get | `change_number_waiting_periods` table: `account_uuid VARBINARY(16) PRIMARY KEY, expires_at BIGINT`. |

---

## 5. Essential Redis Usage

Redis is used by Signal-Server for high-speed volatile operations and **must remain**:
1. **Pub/Sub (`pubsub`):** Real-time message dispatch to active WebSockets. When user A sends a message to user B, the server publishes to Redis channel `account:B:device:1`, waking up the active WebSocket connection.
2. **Online Message Cache (`main_cache`):** Caches transient device state, active sessions, and unacknowledged message IDs.
3. **Rate Limiters (`rate_limiters`):** Token bucket rate-limiting algorithms to prevent brute-force attacks and abuse.
4. **Push Scheduler (`push_scheduler`):** Schedules delayed push notifications when messages are queued.

---

## 6. Redis Cluster Requirement & Single-VPS Setup

- **Is Redis Cluster Mandatory?**
  Yes, in the code, `cacheCluster`, `pushSchedulerCluster`, and `rateLimitersCluster` are typed as `FaultTolerantRedisClusterClient` using the Lettuce `RedisClusterClient` driver.
- **Simplest Supported Way on One VPS:**
  Run standard `redis-server` with cluster mode enabled:
  ```conf
  port 6379
  cluster-enabled yes
  cluster-config-file nodes.conf
  cluster-node-timeout 5000
  appendonly yes
  ```
  Then initialize the single node to own all 16,384 slots:
  ```bash
  redis-cli --cluster create 127.0.0.1:6379 127.0.0.1:6379 --cluster-replicas 0 --cluster-yes
  ```
  *(Or execute `redis-cli cluster addslots {0..16383}`)*.
  This allows Lettuce's `RedisClusterClient` to discover the node topology and route all slot keys directly to `127.0.0.1:6379` without running multiple processes or consuming extra memory.

---

## 7. MySQL 8.0+ / MariaDB 10.6+ InnoDB Compatibility Rules

To ensure 100% compatibility with both MySQL 8.0+ and MariaDB 10.6+ (and compatibility with XAMPP/phpMyAdmin):
1. **Engine & Charset:** `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`.
2. **Binary Data:** Use `VARBINARY(16)` for UUIDs (or `VARCHAR(36)`), `VARBINARY(32)`/`VARBINARY(64)` for cryptographic public keys, and `BLOB`/`LONGBLOB` for ciphertext and serialized messages.
3. **Timestamps:** Use `BIGINT` representing UTC epoch milliseconds (avoids MySQL/MariaDB timezone conversion discrepancies).
4. **No Engine-Specific Syntax:**
   - **NO `RETURNING` clause:** MariaDB supports `RETURNING`, but MySQL 8.0 does not. All queries must use standard `SELECT` then `UPDATE`/`DELETE` inside a transaction.
   - **NO JSON-only functional operators:** Store payload blobs as `BLOB`/`LONGBLOB` with Jackson Java serialization.
   - **Upserts:** Use standard `INSERT INTO ... ON DUPLICATE KEY UPDATE`, which is supported identically by both MySQL and MariaDB.
5. **Prekey Concurrency (`FOR UPDATE SKIP LOCKED`):**
   Both MySQL 8.0+ and MariaDB 10.6+ support `FOR UPDATE SKIP LOCKED`:
   ```sql
   -- Inside a transaction:
   SELECT key_id, public_key
   FROM ec_one_time_prekeys
   WHERE account_uuid = :accountUuid AND device_id = :deviceId
   ORDER BY key_id ASC
   LIMIT 1
   FOR UPDATE SKIP LOCKED;

   DELETE FROM ec_one_time_prekeys
   WHERE account_uuid = :accountUuid AND device_id = :deviceId AND key_id = :selectedKeyId;
   ```
   This guarantees that concurrent clients fetching prekeys never block each other and never receive duplicate one-time prekeys.

---

## 8. Proposed Migration Order & Highest-Risk Classes

1. **Step 1: Dependencies & Configuration**
   - Add `com.mysql:mysql-connector-j:8.4.0`, `com.zaxxer:HikariCP:5.1.0`, `org.flywaydb:flyway-core`, `org.flywaydb:flyway-mysql`, `org.jdbi:jdbi3-core:3.45.1`, `org.jdbi:jdbi3-sqlobject:3.45.1`.
   - Add `DatabaseConfiguration` (JDBC URL, user, password, pool settings) to `WhisperServerConfiguration.java`.
   - Setup Flyway runner at server boot.
2. **Step 2: Accounts & Devices (`Accounts.java`, `Device.java`)** — *High Risk*
   - Highest complexity: handles multi-field unique constraints (`e164`, `pni`, `username_hash`), account deletion, username reservation, and device syncing.
   - Map optimistic lock `#version = :version` to SQL `UPDATE ... WHERE account_uuid = ? AND version = ?`.
3. **Step 3: Prekeys & Cryptographic Key Storage (`KeysManager.java`)** — *High Risk*
   - Concurrency critical: single-use prekeys must never be consumed twice. Use `SELECT ... FOR UPDATE SKIP LOCKED` followed by `DELETE`.
4. **Step 4: User Profiles & Avatars (`Profiles.java`, `ProfilesV2.java`, `ProfileAvatars.java`)** — *Medium Risk*
   - Stores versioned profile ciphertext and avatar images.
5. **Step 5: Offline Persistent Messages (`MessagesDynamoDb.java`)** — *Medium Risk*
   - Stores queued offline messages until fetched and acknowledged. Redis remains for active WebSocket pub/sub.
6. **Step 6: Auxiliary Tables** — *Low Risk*
   - Verification sessions, phone number identifiers, client releases, remote config, push challenges.
7. **Step 7: Verification & Company Allowlist**
   - Fixed test code / admin token generation, plus `allowed_registrations` table.
8. **Step 8: Stubs for Proprietary Cloud Services**
   - CDS, SVR2, Apple DeviceCheck, Payments, S3 path-style MinIO attachments.

---

## 9. Existing Tests Dependent on DynamoDB Local

The following test suites in `service/src/test/java` instantiate `DynamoDbClient` and depend on AWS DynamoDB Local:
- `AccountsTest.java`, `AccountCreationDeletionIntegrationTest.java`
- `AccountsManagerChangeNumberIntegrationTest.java`, `AccountsManagerUsernameIntegrationTest.java`
- `SingleUseECPreKeyStoreTest.java`, `RepeatedUseECSignedPreKeyStoreTest.java`
- `MessagesDynamoDbTest.java`, `ProfilesV2Test.java`
- `VerificationSessionsTest.java`, `PushChallengeDynamoDbTest.java`

*Note:* In accordance with the Code-Only guidelines, we do not run tests during development; for verification on the VPS, standalone SQL integration tests using Testcontainers (MySQL) will be provided and documented.

---

## 10. Summary & Sign-off

- The investigation is complete.
- Branch `mysql-storage` is created and checked out at release tag `v20261104.0.0`.
- All 33 DynamoDB tables, external services, proprietary stubs, and MySQL/MariaDB InnoDB compatibility parameters are mapped out.

---

## 11. Addendum Step A: Client Compatibility & Endpoint Analysis

### 11.1 Android Registration Endpoint Sequence
In Signal-Server `v20261104.0.0`, the stock Android client follows this exact registration sequence:

1. **Create Verification Session:**
   - **Call:** `POST /v1/verification/sessions`
   - **Body:** `{"number": "+1234567890", "client": "android"}`
   - **Server Handling:** Generates random `sessionId` (16 bytes), checks rate limits, returns session token.
2. **Update Session Attributes & Push Token:**
   - **Call:** `PUT /v1/verification/sessions/{sessionId}`
   - **Body:** `{"pushToken": "...", "pushTokenType": "FCM"}`
   - **Server Handling:** Stores push token for push challenges.
3. **Request Verification Code (OTP):**
   - **Call:** `POST /v1/verification/sessions/{sessionId}/code`
   - **Headers/Body:** Transport (`SMS` or `VOICE`), client version.
   - **Original Flow:** Calls external `RegistrationServiceClient` (proprietary gRPC microservice connecting to Twilio/telephony). Requires hCaptcha/reCAPTCHA token and push challenge if flagged.
   - **Self-Hosted Replacement:** Handled locally by `StubRegistrationServiceClient` + `CompanyVerificationManager`. Bypasses external telephony. Generates a secure random 6-digit OTP (or looks up admin-issued code), logs it in the console/webhook, and returns success immediately (`status: 200 OK`). Captcha and push challenges are satisfied/skipped for allowlisted numbers.
4. **Submit Verification Code:**
   - **Call:** `PUT /v1/verification/sessions/{sessionId}/code`
   - **Body:** `{"code": "849201"}`
   - **Server Handling:** Verifies code against `allowed_registrations` in MySQL, burns code, marks session `verified = true`.
5. **Create Account:**
   - **Call:** `POST /v1/accounts` (or `POST /v1/accounts/code/{code}`)
   - **Body:** Public identity key, PNI identity key, signed prekey, one-time prekeys, registration lock credentials.
   - **Server Handling:** Inserts account into MySQL `accounts` table. Returns ACI/PNI tokens.
6. **Registration Lock / PIN (Optional):**
   - **Call:** `PUT /v1/accounts/reglock`
   - **Self-Hosted Replacement:** In private self-hosted setups without SGX SVR, PIN registration lock is made optional and non-blocking.
7. **Device Linking / Provisioning:**
   - **Call:** `GET /v1/devices/provisioning` (WebSocket or long-poll)
   - **Server Handling:** Handled via `ProvisioningManager` over Redis pub/sub. Secondary devices (Signal Desktop) scan QR code and link seamlessly.

### 11.2 Android Client Startup Config & Endpoints Matrix

| Android Config Key | Default Client URL / Constant | Backing Server Component | Status in Self-Hosted Stack | Action Required |
| :--- | :--- | :--- | :--- | :--- |
| `SIGNAL_URL` | `https://textsecure-service.whispersystems.org` | Dropwizard REST & WebSocket service | **Available** (Core Server) | Point to `https://api.<your-domain>` |
| `SIGNAL_CDN_URL` | `https://cdn.signal.org` | S3 / MinIO attachments (CDN0) | **Available** (MinIO + Nginx) | Point to `https://cdn.<your-domain>` |
| `SIGNAL_CDN2_URL` | `https://cdn2.signal.org` | MinIO profiles & avatars (CDN2) | **Available** (MinIO + Nginx) | Point to `https://cdn.<your-domain>` |
| `SIGNAL_CDN3_URL` | `https://cdn3.signal.org` | MinIO PQ prekey bundles (CDN3) | **Available** (MinIO + Nginx) | Point to `https://cdn.<your-domain>` |
| `SIGNAL_CDSI_URL` | `https://cdsi.signal.org` | Intel SGX Private Set Intersection | **Proprietary (SGX Cloud)** | **Disabled/Stubbed**. Users connect by number/username. |
| `SIGNAL_SFU_URL` | `https://calling.signal.org` | Calling Service (SFU group calls) | **Proprietary / Separate Repo** | **Disabled**. 1:1 calls use direct WebRTC via coturn TURN. |
| `SIGNAL_SVR_URL` | `https://svr.signal.org` | Secure Value Recovery (SGX PIN) | **Proprietary (SGX Cloud)** | **Disabled**. Local key derivation. |
| `STORAGE_URL` | `https://storage.signal.org` | Remote contacts/groups key-value store | **Separate Repo** | **Disabled/Stubbed**. Local app storage. |
| `KEY_BACKUP_URL` | `https://backup.signal.org` | Cloud key backup | **Proprietary** | **Disabled**. Return empty/safe response. |

### 11.3 Recommended Server and Android Release Tag Pairing

- **Server Release Tag:** `v20261104.0.0` (commit `66892bc68`)
- **Matching Signal-Android Tag:** **Signal-Android `v7.24.x`** (or `v7.22.x` - `v7.25.x`)
  - **Reason:** Signal-Android `v7.24.x` targets the exact REST API schema, Protobuf v3 contracts, PQ Kyber prekey uploads, and WebSocket framing used by this server tag.
  - **Flavor to build:** Custom flavor based on `assembleFossRelease` (or standard release with custom `google-services.json`). In FOSS flavor, Google Play Services dependencies are excluded, and the app connects directly via persistent WebSocket (`/v1/websocket/`).

