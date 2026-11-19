# PIT MESSENGER – DATABASE MIGRATION ARCHITECTURE

## 1. Overview
This module governs the translation of Signal-Server's legacy cloud storage layer (AWS DynamoDB & FoundationDB) to an enterprise relational **MySQL 8.0+ / MariaDB 10.6+** architecture running on local/private infrastructure (XAMPP or standalone container).

---

## 2. Invariant Storage Rules

1. **Zero Plaintext Invariant**: All messages, attachments, profiles, and ratcheted sessions are stored in ciphertext. The MySQL database never receives, decrypts, or processes plaintext payload content.
2. **Optimistic Locking**:
   - Accounts enforce concurrency via `version BIGINT UNSIGNED`.
   - Update operations strictly evaluate:
     ```sql
     UPDATE accounts
     SET account_data = ?, version = version + 1, last_seen_at = ?
     WHERE account_uuid = ? AND version = ?;
     ```
   - If zero rows are affected, a `ContestedOptimisticLockException` is raised to trigger standard client retry logic.
3. **Atomic One-Time Prekey Handout (`SKIP LOCKED`)**:
   - Handing out one-time prekeys under high concurrency uses:
     ```sql
     SELECT key_id, public_key
     FROM ec_one_time_prekeys
     WHERE account_uuid = ? AND device_id = ?
     ORDER BY key_id ASC
     LIMIT 1
     FOR UPDATE SKIP LOCKED;
     ```
   - Followed by immediate `DELETE` within the same transaction to prevent key reuse.

---

## 3. Schema Structure & Scripts

| Script Path | Target Engine | Purpose |
| :--- | :--- | :--- |
| `schema/01_init_pit_messenger_schema.sql` | MySQL 8.x / MariaDB 10.6+ | Complete initialization DDL for accounts, devices, prekeys, persistent envelopes, and verification sessions. |

---

## 4. Execution via XAMPP MySQL

To apply the schema using XAMPP MySQL in PowerShell:

```powershell
& "C:\xampp\mysql\bin\mysql.exe" -u root -p -e "source D:/signalappppp/database-migration/schema/01_init_pit_messenger_schema.sql"
```
