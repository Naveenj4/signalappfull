# PIT Messenger – Database & Storage Migration Plan (Phase 1)

## 1. Storage Architecture Evaluation Matrix

| Storage Technology | Data Stored | Source Code Modules | Original Signal Purpose | MySQL Migration Feasibility | Transaction & Concurrency Requirements | Status for PIT Messenger |
|---|---|---|---|---|---|---|
| **Oracle MySQL 8.0** | Accounts, Devices, PreKeys, Verification Sessions, Profiles, Messages | `MySqlAccounts`, `MySqlKeys`, `MySqlMessages`, `VerificationController` | Replaces DynamoDB and FoundationDB for self-hosted deployments | **Native Target** | ACID compliant, InnoDB row-level locking, foreign key constraints | **ACTIVE** (12 tables initialized in `pit_messenger`) |
| **Redis 5.0 / 7.x** | Ephemeral pub/sub, rate limit buckets, active WebSocket routing | `RedisClusterClient`, `RateLimiters`, `WebSocketConnection` | Sub-millisecond distributed caching and routing table | **Keep Redis** (MySQL not suited for sub-millisecond in-memory cache) | In-memory atomic operations (`INCR`, `EXPIRE`, `PUBLISH`) | **ACTIVE** (Running on port 6379) |
| **FoundationDB** | Distributed key-value accounts & prekeys | `FoundationDbClient`, `AccountsManager` | Multi-region distributed transactions | **Migrated to MySQL** via JDBI 3 relational tables | Strict serializability (handled by MySQL InnoDB transactions) | **MIGRATED TO MYSQL** |
| **AWS DynamoDB** | Legacy accounts and device state | `DynamoDbClient` | Cloud-native AWS NoSQL storage | **Migrated to MySQL** | Eventual consistency (MySQL provides stronger ACID guarantees) | **MIGRATED TO MYSQL** |
| **MinIO / AWS S3** | Encrypted message attachments, avatars | `AttachmentControllerV4`, `S3Client` | Large binary object storage (BLOBs up to 100MB) | **Retain Object Storage** (BLOBs in MySQL would degrade DB cache performance) | Eventual consistency, presigned HTTP streaming | **PLANNED** (MinIO for local, S3 for cloud) |
| **SQLCipher (Client)** | Local chats, contact list, cryptographic sessions | `SignalDatabase.java`, `SQLCipherOpenHelper` | Device-side SQLite encrypted with 256-bit AES | **DO NOT MODIFY** (Critical client security boundary) | Full local ACID transactions | **PRESERVED** |

---

## 2. MySQL Schema Definition (`pit_messenger`)

All 12 relational tables are defined in [`01_init_pit_messenger_schema.sql`](file:///D:/signalappppp/database-migration/schema/01_init_pit_messenger_schema.sql):
1. `accounts`: UUID, E.164 phone, identity key, registered timestamp.
2. `devices`: Account UUID, device ID (1 = primary), public key, auth token, FCM token, last seen.
3. `signed_prekeys`: Device signed prekeys, signature, key ID.
4. `ec_one_time_prekeys`: One-time prekeys for initial X3DH session handshakes.
5. `paged_pq_prekeys`: Post-quantum Kyber/ML-KEM prekeys.
6. `persistent_messages`: Offline encrypted ciphertext queue.
7. `profiles_v1` & `profiles_v2`: Encrypted profile name, avatar pointers, commitment hashes.
8. `verification_sessions`: Registration challenge state, E.164 number, verification status.
9. `allowed_registrations`: Allowed phone numbers, OTP hashes, attempt counters, expiration.
10. `phone_number_identifiers`: PNI and ACI mapping for phone number privacy.
11. `used_link_device_tokens`: Linked secondary device pairing tokens.

---

## 3. Migration Safety & ACID Guarantees
- **Transactions**: Every account registration and prekey batch insertion runs within an explicit `@Transaction` boundary in JDBI.
- **Connection Pooling**: Managed by HikariCP with a minimum of 10 and maximum of 50 connections.
- **Zero Plaintext**: All table columns holding keys or messages store binary data or hex-encoded public keys. Plaintext messages never touch MySQL.
