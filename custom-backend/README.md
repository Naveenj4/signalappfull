# PIT MESSENGER – CUSTOM BACKEND & REGISTRATION SERVICE SPECIFICATION

## 1. Overview
The Custom Backend microservice decouples PIT Messenger from commercial SMS providers (e.g., Twilio/Infobip) and proprietary cloud verifiers. It introduces a private, self-hosted OTP verification engine compatible with local telecom modems, private SMPP SMS gateways, corporate email dispatch, or admin bypass workflows.

---

## 2. End-to-End Registration Flow

```
[Android App]               [Signal-Server]          [Custom OTP Backend]        [SMS / Gateway]
      |                            |                          |                         |
      |-- 1. Request Code -------->|                          |                         |
      |   (E.164 phone number)     |-- 2. Dispatch OTP ------>|                         |
      |                            |                          |-- 3. Send SMS --------->|
      |                            |                          |    (6-digit token)      |
      |<-- 4. Session Created -----|<-- Session Ref ----------|                         |
      |                                                                                 |
 [User receives SMS]                                                                    |
      |                                                                                 |
      |-- 5. Submit Code & Salt -->|                                                    |
      |                            |-- 6. Verify Code ------->|                         |
      |                            |                          |-- Hash & Expiry Check   |
      |                            |<-- 7. Verification OK ---|                         |
      |<-- 8. Registration Token --|                                                    |
      |                                                                                 |
      |-- 9. Complete Account Registration (Cryptographic Keys & Prekeys) ------------->|
```

---

## 3. API Contract Specifications

### 3.1 Request Verification Code
- **Endpoint**: `POST /v1/registration/request-code`
- **Request Body**:
  ```json
  {
    "e164": "+1234567890",
    "transport": "sms",
    "client_id": "pit-android-8.35.2"
  }
  ```
- **Response (200 OK)**:
  ```json
  {
    "session_id": "sess_9f8a7b6c5d4e3f2a1b0c9d8e",
    "expires_in_seconds": 300,
    "resend_available_in_seconds": 60
  }
  ```

### 3.2 Verify Code
- **Endpoint**: `POST /v1/registration/verify-code`
- **Request Body**:
  ```json
  {
    "session_id": "sess_9f8a7b6c5d4e3f2a1b0c9d8e",
    "code": "849201"
  }
  ```
- **Response (200 OK)**:
  ```json
  {
    "verified": true,
    "verification_token": "tok_v1_c9a8b7e6f5d4c3b2a1",
    "e164": "+1234567890"
  }
  ```

---

## 4. Security Controls
1. **Rate Limiting**: Maximum 5 attempts per session. Maximum 3 code requests per E.164 per hour.
2. **Code Cryptographic Hashing**: Raw OTP codes are hashed with SHA-256 and a per-session cryptographic salt before being written to `verification_sessions`.
3. **Replay Protection**: Verification tokens expire in 10 minutes and can be consumed exactly once.
