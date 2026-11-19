# INITIAL DEPENDENCY & STORAGE ARCHITECTURE REPORT

**Project**: PIT Messenger  
**Focus**: Original Signal Storage Ecosystem & MySQL Migration Analysis  

---

## 1. Original Signal Storage & Infrastructure Matrix

| Technology | Subsystem / Role | Data Stored | Characteristics |
| :--- | :--- | :--- | :--- |
| **FoundationDB** | Distributed Key-Value Store | Ephemeral accounts, message queues, cluster state | Strict serializability, high-write throughput, native C API. |
| **DynamoDB** | NoSQL Primary Store | Accounts, Devices, Pre-Keys, Profiles, Signatures | Managed NoSQL, key-value and document queries, eventual/strong consistency. |
| **Redis / Redis Cluster** | In-Memory Cache & Pub/Sub | Active connection mappings, rate-limiting, ephemeral message push | Ultra-low latency, ephemeral storage, memory-bound. |
| **AWS S3 / CloudFront** | Object Storage & CDN | Encrypted attachment blobs, profile avatars, stickers | Unstructured blob store, presigned PUT/GET URLs, immutable. |
| **SQLCipher (Client)** | Encrypted SQLite | Local message history, contacts, session ratchets, media metadata | AES-256 encrypted, runs locally inside Android app sandbox. |
| **Twilio / Infobip** | SMS / Telephony Gateway | OTP delivery, phone number verification | Third-party SaaS telephony, cost-per-message. |

---

## 2. Long-Term MySQL Migration Feasibility Analysis

The project vision entails utilizing **MySQL (XAMPP / Standalone MySQL 8.x)** on private infrastructure to manage suitable backend application data.

### 2.1 Suitable for Migration to MySQL
1. **Accounts & Devices**:
   - Entities: `accounts`, `devices`, `identities`.
   - Rationale: Relational entity model, standard CRUD semantics, manageable row volume, ACID transaction requirements.
2. **Pre-Keys & Signed Pre-Keys**:
   - Entities: `one_time_prekeys`, `signed_prekeys`.
   - Rationale: Relational lookup by `(account_uuid, device_id, key_id)` with transactional consumption (`SELECT ... FOR UPDATE` or `SKIP LOCKED`).
3. **Profiles & Metadata**:
   - Entities: `profiles`, `profile_keys`, `badges`.
   - Rationale: Infrequent updates, simple key-value or JSON column representations.
4. **Custom OTP & Verification**:
   - Entities: `verification_codes`, `registration_attempts`, `rate_limit_logs`.
   - Rationale: Standard relational records with index-based expiration cleanup.

### 2.2 Components Recommended to Keep or Migrate Independently
1. **Encrypted Attachments**:
   - Do NOT store large attachment binaries in MySQL BLOBs.
   - Recommended: Local S3-compatible object store (e.g., MinIO on drive `D:\`) with presigned upload/download URLs, preserving the existing client-server attachment protocol.
2. **Real-Time Pub/Sub & Presence**:
   - Maintain Redis for active WebSocket session discovery and ephemeral routing to prevent excessive database lock contention.
3. **Client-Side SQLCipher**:
   - Must remain strictly untouched to preserve client-side zero-knowledge encryption and local SQLite performance.

---

## 3. Preservation Guarantees

Any future migration to MySQL must strictly guarantee:
1. **Cryptographic Invariants**: The database stores ciphertext only. No plaintext or derived symmetric keys ever touch the database.
2. **Transaction Isolation**: High concurrency pre-key consumption must prevent duplicate key issuance using atomic row-level locks.
3. **Delivery Semantics**: Message queues must guarantee at-least-once delivery with monotonic sequence ordering.
