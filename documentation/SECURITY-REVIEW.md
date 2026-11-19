# PIT Messenger – Security & Cryptographic Review (Phase 1)

## 1. Cryptographic Invariants & Guarantees
PIT Messenger strictly preserves Signal's end-to-end encryption standards:
- **X3DH (Extended Triple Diffie-Hellman)**: Mutual client authentication without relying on server honesty.
- **Double Ratchet Algorithm**: Provides Forward Secrecy (past messages cannot be decrypted if keys are compromised) and Break-in Recovery (future keys automatically recover confidentiality after compromise).
- **Client SQLCipher Database**: Client-side storage is encrypted using SQLCipher (256-bit AES-CBC) with PBKDF2 key derivation.
- **Zero Plaintext on Server**: Neither `signal-server`, MySQL database, Redis, nor object storage holds decryption keys.

---

## 2. OTP & Registration Threat Analysis

| Threat Vector | Mitigation in PIT Messenger |
|---|---|
| **Brute Force OTP Guessing** | Maximum 5 attempts allowed per session challenge. On 5th failure, session is permanently locked in MySQL. |
| **OTP Interception & Replay** | OTP challenges are single-use. The moment a code is verified, it is marked `consumed` and invalidated. |
| **Database Compromise (OTP Exposure)** | Plaintext OTP is never stored in MySQL; only cryptographically salted SHA-256 hashes are persisted. |
| **SMS Flooding / Wallet Drain** | Rate limiter enforces 60-second cooldown per phone number and IP-based rate limiting (max 5 requests/hour per IP). |
| **MITM on Registration** | Android client communicates strictly over HTTPS / TLS 1.3 with certificate validation. |
| **API Key Exposure** | 2Factor.in credentials are loaded from environment variables (`$env:TWOFACTOR_API_KEY`) and never committed to Git. |

---

## 3. Server Security & Infrastructure Hardening
1. **Network Boundary**: Dropwizard application binds port 8080 internally behind an Nginx reverse proxy with TLS 1.3 termination.
2. **Admin Port Isolation**: Port 8081 (Dropwizard Admin & Metrics) is bound strictly to `127.0.0.1` and not accessible from the public internet.
3. **Database Security**: MySQL user `pituser` has privileges restricted to the `pit_messenger` database only, with `GRANT SELECT, INSERT, UPDATE, DELETE` without administrative or file privileges.
