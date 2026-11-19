# PIT Messenger – 2Factor.in Real OTP Integration Report (Phase 1)

## 1. Selected SMS Provider
- **Provider**: 2Factor.in (Enterprise SMS Gateway)
- **Official Portal**: https://2factor.in
- **Purpose**: Real-time SMS cellular delivery for PIT Messenger phone verification in India and international destinations.
- **Implementation Status**: ANALYSIS COMPLETE (Phase 1)

---

## 2. Official API Investigation & Specifications

### 2.1. API Endpoints and Protocols
- **Production API Base URL**: `https://2factor.in/API/V1/`
- **Supported Methods**: `GET` / `POST` over TLS 1.3
- **Authentication**: API Key passed directly in the URL path segment: `/API/V1/{API_KEY}/...`

### 2.2. Comparison of Implementation Models

#### Model A – Provider-Managed OTP (AutoGen Mode)
1. **Send Request**:
   ```http
   GET https://2factor.in/API/V1/{TWOFACTOR_API_KEY}/SMS/{E164_PHONE}/AUTOGEN/{TWOFACTOR_TEMPLATE_NAME}
   ```
   **Response (JSON)**:
   ```json
   {
     "Status": "Success",
     "Details": "f081498b-7153-4813-9580-c1e183748281"
   }
   ```
   - `Details` contains the provider transaction `session_id`.
   - The backend stores this `session_id` in `allowed_registrations` mapped to the user's E.164 number.
2. **Verification Request**:
   ```http
   GET https://2factor.in/API/V1/{TWOFACTOR_API_KEY}/SMS/VERIFY/{TWOFACTOR_SESSION_ID}/{OTP_VALUE}
   ```
   **Response (JSON)**:
   ```json
   {
     "Status": "Success",
     "Details": "OTP Matched"
   }
   ```
   Or on failure:
   ```json
   {
     "Status": "Error",
     "Details": "OTP Mismatch"
   }
   ```

#### Model B – Application-Managed OTP (Custom Transactional Mode)
1. **Generation**: PIT Messenger backend generates a cryptographically secure 6-digit random code (`SecureRandom`).
2. **Storage**: Backend hashes code (`HMAC-SHA256` or salted `SHA-256`), stores hash, expiration (600s), and attempt count (0/5) in MySQL `allowed_registrations`.
3. **Dispatch**: Backend calls 2Factor transactional SMS API:
   ```http
   GET https://2factor.in/API/V1/{TWOFACTOR_API_KEY}/SMS/{E164_PHONE}/{OTP_VALUE}/{TWOFACTOR_TEMPLATE_NAME}
   ```
4. **Verification**: When user submits OTP to `PUT /v1/verification/session/{id}/code`, backend verifies the code locally with zero external network latency and instant rate-limit enforcement.

### 2.3. Architectural Recommendation
- **Primary Recommendation**: **Model B** provides the highest reliability under Indian DLT regulation because:
  1. Instant local verification with zero reliance on external provider uptime during user OTP submission.
  2. Strict replay attack protection and automatic challenge invalidation upon single-use match.
  3. Seamless provider abstraction: swapping or adding secondary providers (e.g. Exotel, MSG91) requires zero changes to the verification or session state machine.
- **Provider-Managed Support**: The architecture also supports **Model A** via configuration toggle `TWOFACTOR_MANAGED_OTP=true` if AutoGen template approval is granted.

---

## 3. Registration Security Architecture

```
 Android Client                    Signal Server                 2Factor.in Gateway
       |                                 |                               |
       |  1. POST /v1/verif/session       |                               |
       |-------------------------------->| (Create session in MySQL)     |
       |  2. Returns session ID          |                               |
       |<--------------------------------|                               |
       |                                 |                               |
       |  3. POST .../{id}/code          |                               |
       |-------------------------------->| 4. Generate 6-digit OTP       |
       |                                 |    Store salted hash + exp    |
       |                                 | 5. Dispatch SMS               |
       |                                 |------------------------------>|
       |                                 | 6. Returns Status: Success    |
       |                                 |<------------------------------|
       |  7. Returns 200 OK              |                               |
       |<--------------------------------|                               |
       |                                 |    [ Real SMS Delivered ]     |
       |                                 |=============================> User Phone
       |                                 |                               |
       |  8. PUT .../{id}/code (OTP)     |                               |
       |-------------------------------->| 9. Validate hash & attempts   |
       |                                 | 10. Mark session VERIFIED     |
       |  11. Returns verified: true     |                               |
       |<--------------------------------|                               |
       |                                 |                               |
       |  12. PUT /v2/accounts/code/{id} |                               |
       |-------------------------------->| 13. Register Device & PreKeys |
       |  14. Account Created (Token)    |                               |
       |<--------------------------------|                               |
```

### 3.1. Enforced Controls
1. **E.164 Normalization**: Every incoming number is normalized using Google `libphonenumber` (e.g. `+919876543210`).
2. **Salted Hash Storage**: Plaintext OTP is never persisted to database tables; only SHA-256 digests are stored.
3. **Attempt Limit**: Maximum 5 attempts per challenge; upon 5th failure, session is permanently locked.
4. **Cooldown Enforcement**: 60 seconds minimum interval between consecutive OTP requests.
5. **Session Expiry**: Challenges expire in 600 seconds (10 minutes).
6. **No Plaintext Logging**: OTP values are masked in server logs with `[REDACTED_OTP]`.

---

## 4. Affected Source Files

| Repository / Subsystem | Exact File Path | Modification Summary |
|---|---|---|
| `signal-server` | `org.whispersystems.textsecuregcm.registration.SmsSender.java` | Add 2Factor.in HTTP client dispatch with URL encoding & error handling |
| `signal-server` | `org.whispersystems.textsecuregcm.registration.CompanyVerificationManager.java` | Add dynamic OTP generation and attempt counter tracking in MySQL |
| `signal-server` | `org.whispersystems.textsecuregcm.registration.StubRegistrationServiceClient.java` | Bridge 2Factor.in responses into Signal registration session state |
| `signal-server` | `signal-server/config/local.yml` | Add `twoFactor` configuration block (apiKey, apiBaseUrl, template) |
| `custom-backend` | `custom-backend/server.js` | Dedicated standalone microservice endpoint for external portal verification |
