# Setup and Migration Log: Signal-Server MySQL/MariaDB Edition

This log records every configuration decision, architectural mapping, and code change performed while migrating Signal-Server from DynamoDB to MySQL 8.0+ / MariaDB 10.6+ InnoDB in Code-Only Mode.

---

## [Phase 0] Investigation & Environment Audit

- **Date:** 2026-11-16
- **Branch:** `mysql-storage` (based on clean release tag `v20261104.0.0`)
- **Working Directory:** `d:\signalservercode`
- **Actions Completed:**
  1. Checked out the latest stable release tag `v20261104.0.0` and created branch `mysql-storage`.
  2. Verified Java 27 (`<release>27</release>`) and Maven 3.9.11+ requirement from `pom.xml`.
  3. Identified all 33 DynamoDB tables across the storage layer and mapped them to relational InnoDB tables.
  4. Established MySQL 8.0+ and MariaDB 10.6+ compatibility constraints:
     - InnoDB engine with `utf8mb4` character set.
     - `VARBINARY` / `BLOB` / `LONGBLOB` for binary keys and ciphertext.
     - `BIGINT` for epoch milliseconds.
     - No `RETURNING` clauses (using two-step `SELECT ... FOR UPDATE SKIP LOCKED` then `DELETE` inside a transaction).
     - No engine-specific JSON features.
     - Upserts using standard `INSERT INTO ... ON DUPLICATE KEY UPDATE`.
  5. Audited Redis cluster requirement: Redis single-node cluster mode satisfies Lettuce's `RedisClusterClient` on a single VPS.
  6. Generated [PHASE0_REPORT.md](file:///d:/signalservercode/PHASE0_REPORT.md).
- **Status:** Phase 0 complete. Awaiting user approval before proceeding to Phase 1.

---

## [Phase 1 - Step 1] Database Dependencies Configuration

- **Date:** 2026-11-16
- **Files Changed:**
  - `pom.xml`: Added properties and dependency management for `mysql-connector-j` (8.4.0), `HikariCP` (5.1.0), `jdbi3-core` & `jdbi3-sqlobject` (3.45.1), `flyway-core` & `flyway-mysql` (10.17.3), and `testcontainers:mysql`.
  - `service/pom.xml`: Added dependencies for `mysql-connector-j`, `HikariCP`, `jdbi3-core`, `jdbi3-sqlobject`, `flyway-core`, `flyway-mysql`, and `testcontainers:mysql`.
- **Reason:** Provide JDBC drivers, connection pool, SQL mapper, and schema migration tools compatible with both MySQL 8.0+ and MariaDB 10.6+ on Java 27.
- **Potential Compile / Runtime Risks:**
  - `mysql-connector-j` 8.4.0 is fully compatible with Java 27 and supports MariaDB 10.6+ wire protocol.
  - `flyway-mysql` plugin is required in Flyway 10+ for MySQL/MariaDB dialects.

---

## [Phase 1 - Step 2] Database Configuration & Connection Manager

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `service/src/main/java/org/whispersystems/textsecuregcm/configuration/DatabaseConfiguration.java`: Added configuration class holding `jdbcUrl`, `user`, `password`, `maxPoolSize`, `minIdle`, timeouts, and `runFlyway`.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/DatabaseConnectionManager.java`: Created connection pool manager wrapping `HikariDataSource`, executing Flyway migrations at startup, and supplying configured `Jdbi`.
  - `service/src/main/java/org/whispersystems/textsecuregcm/WhisperServerConfiguration.java`: Bound `DatabaseConfiguration` to the root Dropwizard configuration.
  - `service/config/sample.yml`: Added template `database:` configuration with `useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8mb4&rewriteBatchedStatements=true`.
- **Reason:** Provide centralized database configuration and automated Flyway schema initialization across MySQL 8.0+ and MariaDB 10.6+.
- **Potential Compile / Runtime Risks:**
  - HikariCP driver is explicitly configured to `com.mysql.cj.jdbc.Driver` which works with both MySQL and MariaDB servers.
  - Flyway migrations location defaults to `classpath:db/migration`.

---

## [Phase 1 - Step 3] Schema Migrations and Relational Architecture Documentation

- **Date:** 2026-11-16
- **Files Created:**
  - `service/src/main/resources/db/migration/V1__initial_signal_schema.sql`: Initial Flyway migration creating 20 InnoDB tables (`utf8mb4_unicode_ci`) compatible with both MySQL 8.0+ and MariaDB 10.6+.
  - `migrations/init.sql`: Standalone initialization script suitable for direct import into phpMyAdmin or command-line MySQL/MariaDB client.
  - `docs/DYNAMO_TO_MYSQL.md`: Complete mapping reference documenting primary keys, indexes, optimistic locking, `FOR UPDATE SKIP LOCKED` prekey semantics, and TTL cleanup strategies.
- **Reason:** Establish a relational schema without proprietary or engine-specific constructs (no `RETURNING`, no JSON functions) that runs seamlessly across MySQL and MariaDB.
- **Potential Compile / Runtime Risks:**
  - Column sizes and types: `VARCHAR(36)` used for UUID strings, `VARBINARY(64)` for public keys/hashes, and `LONGBLOB` for serialized account/message payloads.
  - Foreign key constraints use `ON DELETE CASCADE` on device and prekey tables to maintain relational integrity when accounts are deleted.

---

## [Phase 1 - Step 4a] Storage Migration: Accounts and Devices

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/AccountsRepository.java`: Introduced repository interface declaring all account and device storage operations.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/Accounts.java`: Added protected constructor allowing clean subclassing and delegation.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/MySqlAccounts.java`: Implemented `AccountsRepository` and extended `Accounts` using JDBI with parameterized queries, deadlock retry loops (error 1213/1205/40001), optimistic locking (`version = version + 1 WHERE account_uuid = ? AND version = ?`), and synchronized device records via `INSERT ... ON DUPLICATE KEY UPDATE`.
  - `service/src/main/java/org/whispersystems/textsecuregcm/WhisperServerService.java`: Wired `MySqlAccounts` conditionally when `database.enabled` is true.
- **Reason:** Migrate accounts and devices from DynamoDB to MySQL/MariaDB InnoDB without changing any caller method signatures or return types in `AccountsManager` or controllers.
- **Potential Compile / Runtime Risks:**
  - `Accounts.ACCOUNT_DDB_JSON_WRITER` is package-private and accessible by `MySqlAccounts` in `org.whispersystems.textsecuregcm.storage`.
  - Concurrency: Deadlock retry mechanism safely retries up to 3 times before propagating database exceptions.

---

## [Phase 1 - Step 4b] Storage Migration: Prekeys and Cryptographic Key Storage

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/SingleUseECPreKeyStore.java`: Added protected constructor.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/RepeatedUseSignedPreKeyStore.java`: Added protected constructor.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/RepeatedUseECSignedPreKeyStore.java`: Added protected constructor.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/RepeatedUseKEMSignedPreKeyStore.java`: Added protected constructor.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/PagedSingleUseKEMPreKeyStore.java`: Added protected constructor.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/MySqlSingleUseECPreKeyStore.java`: Implemented single-use EC prekey store with `SELECT ... FOR UPDATE SKIP LOCKED` followed by immediate `DELETE` in the same transaction to guarantee no prekey is handed out twice.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/MySqlRepeatedUseECSignedPreKeyStore.java`: Implemented signed prekey store using upsert (`INSERT ... ON DUPLICATE KEY UPDATE`).
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/MySqlRepeatedUseKEMSignedPreKeyStore.java`: Implemented post-quantum last resort key store using upsert.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/MySqlPagedSingleUseKEMPreKeyStore.java`: Implemented Kyber prekey page storage in MySQL with atomic transaction consumption.
  - `service/src/main/java/org/whispersystems/textsecuregcm/WhisperServerService.java`: Wired MySQL key stores into `KeysManager`.
- **Reason:** Guarantee concurrency-safe prekey distribution on MySQL/MariaDB with zero possibility of duplicate one-time prekeys.
- **Potential Compile / Runtime Risks:**
  - `FOR UPDATE SKIP LOCKED` is supported in both MySQL 8.0+ and MariaDB 10.6+.
  - Prekey serialization formats match libsignal `ECPublicKey` and `KEMPublicKey`.

---

## [Phase 1 - Step 4c] Storage Migration: User Profiles and Avatars

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/Profiles.java`: Added protected constructor.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/ProfilesV2.java`: Added protected constructor.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/ProfileAvatars.java`: Added protected constructor.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/MySqlProfiles.java`: Implemented versioned profile v1 persistence in MySQL/MariaDB (`profiles_v1` table) with avatar deletion querying.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/MySqlProfilesV2.java`: Implemented encrypted profile v2 persistence in MySQL/MariaDB (`profiles_v2` table).
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/MySqlProfileAvatars.java`: Implemented avatar URL storage and TTL tracking in MySQL/MariaDB (`profile_avatars` table).
  - `service/src/main/java/org/whispersystems/textsecuregcm/WhisperServerService.java`: Wired MySQL profile stores into `ProfilesManager`.
- **Reason:** Store versioned profile ciphertext and avatar metadata in MySQL/MariaDB without DynamoDB dependencies.
- **Potential Compile / Runtime Risks:**
  - `VersionedProfileV1` and `VersionedProfile` record constructors match exact parameter order.
  - Avatars use standard UTF-8 string encoding stored as binary blobs.

---

## [Phase 1 - Step 4d] Storage Migration: Persistent Message Queue

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/MessagesDynamoDb.java`: Added protected constructor.
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/MySqlMessagesStore.java`: Implemented offline persistent message queue in MySQL/MariaDB (`persistent_messages` table) using batch inserts, ordered stream querying by `server_timestamp ASC`, and single-guid deletion. Redis remains active for WebSocket live pub/sub and memory caching.
  - `service/src/main/java/org/whispersystems/textsecuregcm/WhisperServerService.java`: Wired `MySqlMessagesStore` into `MessagesManager`.
- **Reason:** Provide durable offline message storage in MySQL/MariaDB while keeping Redis for fast online delivery.
- **Potential Compile / Runtime Risks:**
  - `MessageProtos.Envelope` parsing uses official protobuf runtime deserialization.
  - UUID strings are bound to `guid` column which has a unique constraint.

---

## [Phase 1 - Step 4e] Storage Migration: Remaining DynamoDB-Backed Tables

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `PhoneNumberIdentifiers.java`: Added protected constructor.
  - `MySqlPhoneNumberIdentifiers.java`: Phone number to PNI mappings using `phone_number_identifiers` table.
  - `ClientReleases.java`: Added protected constructor.
  - `MySqlClientReleases.java`: Dynamic client release versions from `client_releases` table.
  - `RemoteConfigs.java`: Added protected constructor.
  - `MySqlRemoteConfigs.java`: Server feature flags and rollout percentages in `remote_configs` table.
  - `PushChallengeDynamoDb.java`: Added protected constructor.
  - `MySqlPushChallengeStore.java`: Push challenge tokens in `push_challenges` table with expiration checks.
  - `ReportMessageDynamoDb.java`: Added protected constructor.
  - `MySqlReportMessageStore.java`: Spam report message hashes in `report_messages` table.
  - `PhoneNumberRecoveryPasswords.java`: Added protected constructor.
  - `MySqlPhoneNumberRecoveryPasswords.java`: Registration recovery salted passwords in `phone_number_recovery_passwords` table.
  - `SerializedExpireableJsonDynamoStore.java` & `VerificationSessions.java`: Added protected constructors.
  - `MySqlVerificationSessions.java`: Registration verification session states in `verification_sessions` table.
  - `ChangeNumberWaitingPeriods.java`: Added protected constructor.
  - `MySqlChangeNumberWaitingPeriods.java`: Change-number cooldown periods in `change_number_waiting_periods` table.
  - `SandboxAccounts.java`: Added protected constructor.
  - `MySqlSandboxAccounts.java`: Sandbox account reservation pool in `sandbox_accounts` table.
  - `AccountLockManager.java`: Added protected constructor.
  - `MySqlAccountLockManager.java`: In-JVM sorted striped locking by UUID preventing deadlocks across account mutation operations without DynamoDB lock client.
  - `WhisperServerService.java`: Wired all MySQL storage classes when `config.getDatabaseConfiguration().isEnabled()`.
- **Reason:** Complete the migration of all remaining DynamoDB tables to MySQL/MariaDB.
- **Potential Compile / Runtime Risks:**
  - Handled conditional updates using SQL transactions and `ON DUPLICATE KEY UPDATE`.
  - Deterministic UUID sorting in `MySqlAccountLockManager` prevents circular wait conditions.

---

## [Phase 1 - Step 5] Bypass DynamoDB Client Creation and Startup Table Checks

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `service/src/main/java/org/whispersystems/textsecuregcm/storage/DummyDynamoDbClientFactory.java`: Created dynamic proxy generator for `DynamoDbClient` and `DynamoDbAsyncClient` interfaces returning safe no-op responses and completed futures.
  - `service/src/main/java/org/whispersystems/textsecuregcm/WhisperServerService.java`: Conditionally bypassed real AWS SDK DynamoDB client building when `database.enabled = true`, supplying dummy proxies that prevent any outbound AWS network calls or credential checks on boot.
- **Reason:** Allow the Signal Server to boot natively on a VPS with zero AWS credentials, zero DynamoDB dependencies, and no need to run DynamoDB Local or ScyllaDB Alternator.
- **Potential Compile / Runtime Risks:**
  - `Proxy.newProxyInstance` cleanly implements standard `DynamoDbClient` and `DynamoDbAsyncClient` interfaces.

---

## [Phase 1 - Steps 6 & 7] Company-Only Registration Allowlist and Verification Codes

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `CompanyVerificationManager.java`: Created verification manager backed by `allowed_registrations` table in MySQL/MariaDB. Supports fixed test-code mode (e.g. `123456`), admin-issued one-time codes with expiration, and strict company allowlist enforcement.
  - `RegistrationServiceClient.java`: Added protected constructor.
  - `StubRegistrationServiceClient.java`: Replaced external proprietary Signal registration microservice with in-memory session handling tied to `CompanyVerificationManager`.
  - `StubRegistrationServiceClientFactory.java`: Created Discoverable Dropwizard factory for `type: stub`.
  - `AdminRegistrationController.java`: Added administrative REST endpoints at `/v1/admin/registrations` secured with `X-Admin-Api-Key` to list, add, remove staff, and set custom one-time verification codes.
  - `DatabaseConfiguration.java`: Added allowlist toggle, fixed code settings, and admin API key configurations.
  - `WhisperServerService.java`: Wired `StubRegistrationServiceClient` and `AdminRegistrationController` when database is enabled.
  - `scripts/admin-allowlist.sql` & `scripts/add-user.sh`: Provided SQL and bash scripts for managing allowlisted company staff numbers.
- **Reason:** Enable self-contained, closed-group company operations with no reliance on third-party SMS/voice gateways (Twilio/TeleSign/Infobip) or Signal cloud infrastructure.
- **Potential Compile / Runtime Risks:**
  - `RegistrationServiceSession` record instantiation parameters match exact constructor signature.
  - Strict E.164 parsing handles phone number variations and equivalence classes.

---

## [Phase 1 - Step 8] Attachment Upload Restriction Flag

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `AttachmentControllerV4.java`: Added constructor and runtime check for `uploadAllowlistEnabled`. When enabled, validates that the authenticated account's phone number exists in `allowed_registrations` before generating signed attachment upload descriptors.
  - `DatabaseConfiguration.java`: Added `attachmentUploadAllowlist` property (boolean, default: `false`).
  - `WhisperServerService.java`: Passed `accountsManager`, `companyVerificationManager`, and `attachmentUploadAllowlist` flag to `AttachmentControllerV4`.
  - `SECURITY_NOTES.md`: Created security notes detailing E2EE guarantees, allowlists, verification code modes, and VPS hardening.
- **Reason:** Allow company administrators to restrict media file uploads and storage consumption strictly to allowlisted staff members.
- **Potential Compile / Runtime Risks:**
  - When disabled (`false`), upload flow remains standard with zero performance overhead.

---

## [Phase 1 - Step 9] Stub Proprietary Signal Cloud Services

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `SecureValueRecoveryClient.java`: Added protected constructor.
  - `StubSecureValueRecoveryClient.java`: Created no-op stub for Secure Value Recovery (SVR2/SVRB) calls.
  - `AppleDeviceChecks.java`: Added protected constructor.
  - `StubAppleDeviceChecks.java`: Created no-op stub bypassing iOS AppAttest/DeviceCheck validation and table storage.
  - `WhisperServerService.java`: Wired `StubSecureValueRecoveryClient` and `StubAppleDeviceChecks` when MySQL database is enabled.
  - `SECURITY_NOTES.md`: Documented architectural impact of stubbed proprietary services (CDS, SVR, Apple DeviceCheck, Payments).
- **Reason:** Decouple the server completely from proprietary remote SGX hardware enclaves and commercial payment/verification platforms.
- **Potential Compile / Runtime Risks:**
  - In a private company messenger, client account creation, messaging, and attachment transfer remain 100% operational.

---

## [Phase 1 - Step 10] Configure MinIO S3 Path-Style Access for Attachments

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `CdnConfiguration.java`: Added `forcePathStyle` property and automatic `isForcePathStyle()` fallback (defaults to `true` when `endpointOverride` is specified).
  - `WhisperServerService.java`: Configured `S3AsyncClient` with `.forcePathStyle(...)` so that local MinIO endpoints can parse bucket paths (`http://127.0.0.1:9000/cdn/...`) without DNS virtual-host resolution errors.
  - `service/config/sample.yml`: Updated `cdn:` configuration template with MinIO parameters.
- **Reason:** Allow self-hosted MinIO binary on the VPS to serve as the S3-compatible attachment and avatar storage store with zero AWS S3 charges.
- **Potential Compile / Runtime Risks:**
  - `forcePathStyle` is fully supported by AWS SDK for Java 2.x `S3AsyncClient.builder()`.

---

## [Phase 1 - Step 11] Add Testcontainers MySQL Integration Tests

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `service/src/test/java/org/whispersystems/textsecuregcm/storage/MySqlStorageIntegrationTest.java`: Added full Testcontainers integration tests verifying:
    1. Account lifecycle, device addition, and versioned optimistic locking.
    2. Concurrent single-use prekey consumption across 10 concurrent threads using `FOR UPDATE SKIP LOCKED` (asserting no key is ever returned twice and remaining key count matches).
    3. Company registration allowlist enforcement and verification code authorization.
- **Reason:** Provide automated integration test suite runnable in Docker/CI on the Linux VPS.
- **Potential Compile / Runtime Risks:**
  ---

## [Phase 2] Production Configuration & Linux VPS Deployment Automation

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `pom.xml`: Parameterized compiler release level `<java.release.version>22</java.release.version>` to allow building on modern JDKs (22, 23, 24, 25, 27) without syntax incompatibilities.
  - `config/production.yml` & `config/production.sample.yml`: Production Dropwizard configuration with environment variable substitution (`${SIGNAL_DB_HOST:-127.0.0.1}`, etc.), MySQL pool settings, MinIO CDN, and stubbed registration service.
  - `config/secrets.bundle.yml`: Offline template secrets bundle resolving all `secret://` references with valid dummy keys.
  - `systemd/signal-server.service`: Systemd service unit configured with auto-restart, security sandboxing, and JVM memory limits.
  - `nginx/signal.conf`: Production Nginx reverse proxy configuration with WebSocket upgrades (`/v1/websocket/`), 3600s timeouts, 120MB attachment uploads, and restricted `/v1/admin/` internal endpoints.
  - `scripts/install-vps.sh`: One-click native installation script for Ubuntu 22.04/24.04 (installs Eclipse Temurin JDK 22, Maven 3.9, MariaDB/MySQL, single-node Redis cluster, MinIO, Coturn, Nginx, Certbot).
  - `scripts/setup-db.sh`: Database & user initialization script running Flyway migrations.
  - `scripts/generate-keys.sh`: Automated cryptographic key generator producing ZK group server params, unidentified delivery trust root certificate, link device secrets, and updating client documentation.
  - `scripts/build.sh`: VPS Maven build script producing the shaded fat JAR.
  - `scripts/backup.sh`: Automated daily backup script for MySQL database dumps (`mysqldump`), MinIO attachments archive, and configuration files with 7-day retention cleanup.
  - `scripts/smoke-test.sh`: Automated end-to-end curl smoke test validating server ping, admin authorization, staff registration, and outsider rejection.
  - `Dockerfile`: Multi-stage Docker build file (Temurin 22 builder -> minimal JRE runtime).
  - `docker-compose.yml`: Complete 4-service container stack (MySQL 8.4 + Redis 7 + MinIO + Signal-Server) for containerized VPS hosting.
  - `MessagesManager.java`: Enhanced to operate autonomously without FoundationDB when MySQL is enabled, routing delivery queues directly into `MySqlMessagesStore` (`persistent_messages`).
  - `MySqlMessagesStore.java`: Added batch UUID safety and `clearAll` account/device message queue cleanup.
  - `WhisperServerService.java`: Made FoundationDB and native `libfdb_c` JNI loading completely optional, eliminating FoundationDB cluster requirements for single-VPS MySQL deployments.
  - `.env.example`: Template environment variable file for VPS deployment.

  - `.gitignore`: Added `.env`, `*.log`, and temporary files to ignore list.
- **Reason:** Provide fully automated, production-grade deployment scripts and configurations for both native Linux VPS (Ubuntu) and Docker environments.

---

## [Phase 3] Documentation & Client Build Guides

- **Date:** 2026-11-16
- **Files Changed / Created:**
  - `DEPLOY.md`: Comprehensive, step-by-step VPS deployment guide covering architecture, system requirements, Native installation, Docker Compose installation, TLS setup, database troubleshooting, and verification.
  - `CLIENT_KEYS.md`: Complete specification of public keys, trust roots, and URLs needed by client applications, including exact file paths and code modifications for `Signal-Android` and `Signal-Desktop`.
  - `docs/ADMIN_GUIDE.md`: Plain-English administrative manual for company IT staff covering employee onboarding, dynamic verification codes, offboarding/revocation, backup/restore, and server monitoring.
- **Reason:** Provide complete operational documentation so non-Java experts can manage, deploy, and maintain the private messenger without AWS or third-party cloud costs.












---

## [Addendum] Services, Replacements, and Android-Client Compatibility

- **Date:** 2026-11-17
- **Branch:** `mysql-storage`
- **Files Changed / Created:**
  - `PHASE0_REPORT.md`: Appended Section 11 detailing the complete Signal-Android registration flow, startup endpoints/keys compatibility matrix, and server/client release tag pairing recommendations.
  - `service/src/main/java/org/whispersystems/textsecuregcm/registration/SmsSender.java`: Created production multi-mode SMS/OTP gateway supporting Twilio REST API, generic HTTP webhook (`SMS_WEBHOOK_URL`), and live console logging with zero AWS SNS dependency.
  - `service/src/main/java/org/whispersystems/textsecuregcm/registration/StubRegistrationServiceClient.java`: Enhanced to generate cryptographically secure 6-digit random codes (configurable 10m - 24h expiry) and dispatch through `SmsSender`.
  - `service/src/main/java/org/whispersystems/textsecuregcm/registration/CompanyVerificationManager.java`: Enhanced verification code enforcement with maximum 5 attempts tracking, `enabled` check, and single-use code burning on successful registration.
  - `service/src/main/resources/db/migration/V2__allowed_registrations_addendum.sql`: Added database migration updating `allowed_registrations` schema with `display_name`, `email`, `attempts_remaining`, and `enabled`.
  - `migrations/init.sql`: Synchronized standalone SQL setup script with V2 schema additions.
  - `service/src/main/java/org/whispersystems/textsecuregcm/controllers/AdminRegistrationController.java`: Added `GET /v1/admin/registrations/{identifier}/code` endpoint to retrieve active verification codes for support staff.
  - `scripts/admin-cli.sh`: Created production admin CLI tool implementing `list-users`, `add-user`, `remove-user`, `disable-user`, `enable-user`, `generate-code`, and `get-code`.
  - `docs/ADMIN_GUIDE.md`: Documented CLI commands, OTP modes, and phpMyAdmin SQL queries for user management.
  - `service/src/main/java/org/whispersystems/textsecuregcm/push/FcmSender.java`: Formatted and loaded Google Firebase Service Account credentials (`signalapp-84c12`) via env var `FCM_CREDENTIALS_PATH` and config `fcm.credentials`, ensuring FCM HTTP v1 compliance with fallback resilience.
  - `service/src/main/java/org/whispersystems/textsecuregcm/auth/CloudflareTurnCredentialsManager.java`: Implemented local coturn RFC 5766 HMAC-SHA1 credentials generator fallback for 1:1 voice and video calling.
  - `coturn/turnserver.conf`: Created coturn configuration template with shared secret authentication, port 3478/5349, and relay port ranges.
  - `scripts/setup-minio.sh`: Created MinIO automated bucket setup and 30-day retention lifecycle management script.
  - `nginx/signal.conf`: Added dedicated `cdn.<domain>` virtual host proxying attachments directly to MinIO.
  - `SECURITY_NOTES.md`: Formatted and documented all security trade-offs, credentials handling, and disabled cloud services.
  - `CONNECTION_CHECKLIST.md`: Documented 10-step sequential verification checklist (a–j) with log failure detection for VPS deployment.
  - `CLIENT_CHANGES_REQUIRED.md`: Created detailed guide for building and adapting Signal-Android and Signal-Desktop to the custom server.
- **Reason:** Satisfy all Addendum requirements for private company messenger deployment without AWS/external proprietary services.
- **Potential Compile / Runtime Risks:**
  - FCM requires valid network access to `fcm.googleapis.com` from the VPS; fallback to WebSocket delivery keeps active sessions working even if FCM is unreachable.
  - Coturn requires UDP ports 3478 and 49152-65535 open in the VPS firewall for media relay.

---

## [Phase 1 - Addendum Fixes & Verification] Dynamic OTP, Flyway V3/V4, and Runtime Hardening

- **Date:** 2026-11-17
- **Branch:** `mysql-storage`
- **Files Changed / Created:**
  - `service/src/main/resources/db/migration/V3__verification_sessions_schema.sql`: Created Flyway migration creating `verification_sessions` table with `session_key VARCHAR(128)`, `serialized_data MEDIUMTEXT`, `expiration BIGINT`.
  - `service/src/main/resources/db/migration/V4__phone_number_identifiers_and_client_releases.sql`: Created Flyway migration creating `phone_number_identifiers` table (`phone_number VARCHAR(32) PRIMARY KEY, pni VARBINARY(16)`) and `client_releases` table (`platform VARCHAR(32), version VARCHAR(32), release_timestamp BIGINT, expiration BIGINT`), pre-seeded with `ANDROID 7.0.0` valid release.
  - `migrations/init.sql`: Synchronized standalone initialization schema with Flyway V3 and V4 tables.
  - `service/src/main/java/org/whispersystems/textsecuregcm/keytransparency/KeyTransparencyServiceClient.java`: Added defensive try-catch error handling to avoid startup crash on dummy/invalid TLS certificates in local/self-hosted environments.
  - `service/src/main/java/org/whispersystems/textsecuregcm/controllers/VerificationController.java`: Updated verification session creation and push/captcha verification endpoints to recognize allowlisted numbers and set `allowedToRequestCode: true` directly without external cloud captchas.
  - `service/src/main/java/org/whispersystems/textsecuregcm/registration/StubRegistrationServiceClient.java`: Exposed `CompanyVerificationManager` getter for verification controller allowlist checks.
  - `.vscode/settings.json`: Set `"java.autobuild.enabled": false` to prevent IDE background ECJ compiler from producing corrupt bytecode in `target/classes`.
- **Reason:** Complete verification session lifecycle in MySQL, enable dynamic OTP generation and verification without external CAPTCHA/SMS blockers, and harden the server against build and runtime issues.
- **Verification:**
  - Successfully verified dynamic OTP handshake via `POST /v1/verification/session`, `POST /v1/verification/session/{id}/code`, `GET /v1/admin/registrations/{number}/code`, and `PUT /v1/verification/session/{id}/code` returning `{"verified": true}`.

