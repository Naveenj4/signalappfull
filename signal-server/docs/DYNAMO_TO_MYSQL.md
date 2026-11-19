# DynamoDB to MySQL 8.0+ / MariaDB 10.6+ Storage Mapping Architecture

This document specifies the exact mapping from AWS DynamoDB NoSQL tables to relational InnoDB tables in MySQL 8.0+ and MariaDB 10.6+ for Signal-Server.

---

## 1. Engine & Character Set Standards

- **Engine:** `InnoDB` across all tables.
- **Charset & Collation:** `utf8mb4` with `utf8mb4_unicode_ci` collation.
- **Cross-Engine Compatibility:**
  - **No `RETURNING` clauses:** Supported in MariaDB 10.6+ but rejected by MySQL 8.0. All inserts/updates/deletes use standard SQL.
  - **No engine-specific JSON functions:** Payloads are serialized as JSON by Jackson into binary `LONGBLOB` fields.
  - **Explicit Primary Keys:** All tables define an explicit `PRIMARY KEY`.
  - **Binary Fields:** `VARBINARY` is used for public keys, token hashes, and small credentials; `LONGBLOB` is used for serialized account/message payloads.
  - **Timestamps:** Handled as `BIGINT` representing milliseconds since Unix epoch (avoiding time zone discrepancies).

---

## 2. Table-by-Table Architectural Mapping

| DynamoDB Table | DynamoDB Key / Indexes | MySQL / MariaDB Table | Relational Schema & Primary Key | Access Pattern / Translation |
| :--- | :--- | :--- | :--- | :--- |
| **Accounts** | PK: `account_uuid`<br>GSI: `username_link_uuid` | `accounts` | `PRIMARY KEY (account_uuid)`<br>`UNIQUE (e164)`<br>`UNIQUE (pni)`<br>`UNIQUE (username_hash)`<br>`UNIQUE (username_link_uuid)` | Account data serialized as JSON into `account_data LONGBLOB`. Optimistic locking via `WHERE account_uuid = ? AND version = ?`. |
| **Phone Number Constraint** | PK: `e164` | `accounts` | Enforced via `UNIQUE KEY (e164)` | Replaces DynamoDB conditional put with standard database unique constraint. |
| **PNI Constraint** | PK: `pni` | `accounts` | Enforced via `UNIQUE KEY (pni)` | Replaces DynamoDB conditional put with unique constraint. |
| **Usernames Constraint** | PK: `username_hash` | `accounts` | Enforced via `UNIQUE KEY (username_hash)` | Unique constraint prevents duplicate usernames. |
| **Devices** | Nested in Account object | `devices` | `PRIMARY KEY (account_uuid, device_id)`<br>`FOREIGN KEY (account_uuid) REFERENCES accounts(account_uuid) ON DELETE CASCADE` | Normalized table for device metadata and push credentials. |
| **Used Link Tokens** | PK: `token_hash` | `used_link_device_tokens` | `PRIMARY KEY (token_hash)` | Token insertion via `INSERT IGNORE` or `ON DUPLICATE KEY UPDATE`. |
| **Deleted Accounts** | PK: `account_pni`<br>GSI: `account_uuid` | `deleted_accounts` | `PRIMARY KEY (account_pni)`<br>`INDEX (account_uuid)` | Preserves previous ACI mapping upon account unregistration. |
| **EC One-Time Prekeys** | PK: `account_uuid`, `device_id:key_id` | `ec_one_time_prekeys` | `PRIMARY KEY (account_uuid, device_id, key_id)`<br>`FOREIGN KEY ON DELETE CASCADE` | Fetched with `SELECT ... FOR UPDATE SKIP LOCKED` then `DELETE` inside a transaction. |
| **Paged PQ Prekeys** | PK: `account_uuid`, `device_id` | `paged_pq_prekeys` | `PRIMARY KEY (account_uuid, device_id, batch_id)`<br>`FOREIGN KEY ON DELETE CASCADE` | Stores Kyber prekey pages. |
| **EC Signed Prekeys** | PK: `account_uuid`, `device_id` | `signed_prekeys` | `PRIMARY KEY (account_uuid, device_id, key_type)`<br>`WHERE key_type = 'EC'` | Upserted via `INSERT ... ON DUPLICATE KEY UPDATE`. |
| **PQ Last Resort Keys** | PK: `account_uuid`, `device_id` | `signed_prekeys` | `PRIMARY KEY (account_uuid, device_id, key_type)`<br>`WHERE key_type = 'PQ_LAST_RESORT'` | Stored in unified `signed_prekeys` table. |
| **Profiles V1** | PK: `account_uuid`, `version` | `profiles_v1` | `PRIMARY KEY (account_uuid, version)` | Stores profile attributes and avatar path. |
| **Profiles V2** | PK: `account_uuid`, `version` | `profiles_v2` | `PRIMARY KEY (account_uuid, version)` | Stores versioned encrypted profile ciphertext. |
| **Profile Avatars** | PK: `identity` | `profile_avatars` | `PRIMARY KEY (identity)` | Raw avatar image bytes stored in `avatar_data LONGBLOB`. |
| **Messages (Offline)** | PK: `partition`, `sort`<br>LSI: `guid` | `persistent_messages` | `PRIMARY KEY (id AUTO_INCREMENT)`<br>`UNIQUE KEY (guid)`<br>`INDEX (destination_uuid, destination_device_id, server_timestamp)` | Offline persistent queue for undelivered messages. Redis remains for active WebSocket pub/sub. |
| **Verification Sessions** | PK: `session_id` | `verification_sessions` | `PRIMARY KEY (session_id)` | Session state and SMS verification codes. |
| **Phone Number Recovery** | PK: `pni` | `phone_number_recovery_passwords` | `PRIMARY KEY (pni)` | Password hash for number recovery. |
| **Remote Config** | PK: `name` | `remote_configs` | `PRIMARY KEY (name)` | Configuration key-value store. |
| **Client Releases** | PK: `platform`, `version` | `client_releases` | `PRIMARY KEY (platform, version)` | Track minimum and current client app versions. |
| **Push Challenges** | PK: `token` | `push_challenges` | `PRIMARY KEY (token)` | Ephemeral push token challenges. |
| **Report Messages** | PK: `token` | `report_messages` | `PRIMARY KEY (token)` | User spam report payloads. |
| **Change Number Waiting** | PK: `account_uuid` | `change_number_waiting_periods` | `PRIMARY KEY (account_uuid)` | Change number rate-limiting locks. |
| **Phone Number Identifiers** | PK: `phone_number`<br>GSI: `pni` | `phone_number_identifiers` | `PRIMARY KEY (phone_number)`<br>`INDEX (phone_number_identifier)` | Maps E.164 phone numbers to PNIs. |
| **Allowed Registrations** | N/A (New feature) | `allowed_registrations` | `PRIMARY KEY (identifier)` | Company-internal employee allowlist and admin-generated verification codes. |

---

## 3. Concurrency & Transaction Translation

### 3.1 Optimistic Locking
In DynamoDB, updates use `#version = :version`. If another process modified the account concurrently, a `ConditionalCheckFailedException` is raised.
In MySQL / MariaDB, this translates to:
```sql
UPDATE accounts
SET account_data = :accountData,
    e164 = :e164,
    pni = :pni,
    username_hash = :usernameHash,
    username_link_uuid = :usernameLinkUuid,
    version = version + 1,
    canonically_discoverable = :canonicallyDiscoverable,
    last_seen_at = :lastSeenAt
WHERE account_uuid = :accountUuid AND version = :version;
```
If 0 rows are updated:
- The server checks if `account_uuid` exists.
- If it exists, a `ContestedOptimisticLockException` is thrown, matching the DynamoDB behavior.
- If it does not exist, an `IllegalArgumentException` is thrown.

### 3.2 Single-Use Prekey Concurrency (`SKIP LOCKED`)
DynamoDB uses conditional deletions with client-side retries when multiple messages arrive simultaneously for the same recipient.
In MySQL 8.0+ and MariaDB 10.6+, both engines support `FOR UPDATE SKIP LOCKED`.
To guarantee that a one-time prekey is **never** handed out twice and callers never block each other:
```sql
-- Step 1 (inside a transaction): select one key non-blocking
SELECT key_id, public_key
FROM ec_one_time_prekeys
WHERE account_uuid = :accountUuid AND device_id = :deviceId
ORDER BY key_id ASC
LIMIT 1
FOR UPDATE SKIP LOCKED;

-- Step 2: delete the selected key
DELETE FROM ec_one_time_prekeys
WHERE account_uuid = :accountUuid AND device_id = :deviceId AND key_id = :selectedKeyId;

-- Step 3: commit transaction
```

### 3.3 TTL Expiration Replacement
In DynamoDB, TTL relies on an internal Amazon background reaper that deletes expired items after an arbitrary delay.
In MySQL / MariaDB, cleanup is handled by explicit index scans on `expires_at`:
```sql
DELETE FROM persistent_messages WHERE expires_at < :nowMillis LIMIT 1000;
DELETE FROM verification_sessions WHERE expires_at < :nowMillis LIMIT 1000;
DELETE FROM report_messages WHERE expires_at < :nowMillis LIMIT 1000;
```
A lightweight scheduled background executor periodically runs these cleanup sweeps.
