# PIT Messenger – System Architecture Audit (Phase 1)

## 1. Executive Summary & Core Objective
PIT Messenger is an independent, production-oriented private messaging platform customized from official open-source Signal Android and Signal Server codebases. All communication and verification systems are engineered for real-time cellular and network operations rather than demo or mocked flows.

This architecture audit details the complete end-to-end journey across every layer:
$$\text{Phone Number} \longrightarrow \text{OTP SMS (2Factor.in)} \longrightarrow \text{OTP Verification} \longrightarrow \text{Device Registration} \longrightarrow \text{Secure Messaging} \longrightarrow \text{Message Delivery} \longrightarrow \text{Attachments} \longrightarrow \text{Voice/Video Calling} \longrightarrow \text{Push Notifications}$$

---

## 2. Complete End-to-End Journey Map

| Stage | Triggering Component | Backend API Endpoint | Underlying Storage / Engine | External Integration |
|---|---|---|---|---|
| **1. Registration Request** | `AppRegistrationNetworkController.kt` | `POST /v1/verification/session` | MySQL `verification_sessions`, `allowed_registrations` | Phone format validation (`libphonenumber`) |
| **2. OTP SMS Dispatch** | User taps "Send SMS" | `POST /v1/verification/session/{id}/code` | MySQL `allowed_registrations` (hashed code, expiry) | **2Factor.in API** (`https://2factor.in/API/V1/...`) |
| **3. OTP Verification** | User inputs 6-digit OTP | `PUT /v1/verification/session/{id}/code` | MySQL session verification state flag | 2Factor verify API (Model A) or local cryptographic verification (Model B) |
| **4. Device Registration** | Android generates ACI/PNI, PreKeys | `PUT /v2/accounts/code/{code}` | MySQL `accounts`, `devices`, `signed_prekeys`, `ec_one_time_prekeys` | Signal X3DH cryptographic identity establishment |
| **5. E2EE Messaging** | User sends message | `PUT /v1/messages/{destination}` | MySQL `persistent_messages`, Redis message pub/sub | Signal Double Ratchet, pairwise ciphertext only |
| **6. Message Delivery** | Recipient online / WebSocket | `GET /v1/messages` (or WS push) | MySQL `persistent_messages` deletion on ACK | Push Notification trigger if socket offline |
| **7. Attachments** | User attaches media | `GET /v4/attachments/form/upload` | S3 / MinIO Object Storage | Presigned PUT/GET, client AES-256-GCM ciphertext |
| **8. Voice/Video Calling** | User initiates call | `GET /v2/calling/relays` | WebSockets + RingRTC WebRTC signaling | Coturn STUN/TURN relays (P2P encrypted media) |
| **9. Push Notifications** | Background event | Server dispatch | Redis device queue | Firebase Cloud Messaging (FCM v1 HTTP API) |

---

## 3. Detailed Component Architecture

### 3.1. Client Architecture (Signal-Android)
- **Source Root**: `D:\signalappppp\signal-android\`
- **Branch**: `pit-messenger-custom`
- **Networking Core**: `org.signal.network.api.RegistrationApiV2`, `SignalServiceAccountManager`, `SignalServiceMessageSender`, `SignalWebSocket`
- **Key Modules**:
  - Registration Flow: [`AppRegistrationNetworkController.kt`](file:///D:/signalappppp/signal-android/app/src/main/java/org/thoughtcrime/securesms/registration/v2/AppRegistrationNetworkController.kt)
  - Messaging Engine: `MessageSender.kt`, `SignalServiceMessageSender.java`
  - Calling Wrapper: `WebRtcCallManager.java`, `RingRtc` native library
  - Local Database: SQLCipher encrypted SQLite (`SignalDatabase.java`)

### 3.2. Server Architecture (Signal-Server)
- **Source Root**: `D:\signalappppp\signal-server\`
- **Branch**: `pit-messenger-custom`
- **Framework**: Dropwizard 4.x / Jetty / Jersey / JDBI 3 / HikariCP
- **Controllers**:
  - Verification & OTP: [`VerificationController.java`](file:///D:/signalappppp/signal-server/service/src/main/java/org/whispersystems/textsecuregcm/controllers/VerificationController.java)
  - Registration & PreKeys: [`AccountControllerV2.java`](file:///D:/signalappppp/signal-server/service/src/main/java/org/whispersystems/textsecuregcm/controllers/AccountControllerV2.java), [`KeysController.java`](file:///D:/signalappppp/signal-server/service/src/main/java/org/whispersystems/textsecuregcm/controllers/KeysController.java)
  - Message Queue: [`MessageController.java`](file:///D:/signalappppp/signal-server/service/src/main/java/org/whispersystems/textsecuregcm/controllers/MessageController.java)
  - Calling Relay Credentials: [`CallRoutingControllerV2.java`](file:///D:/signalappppp/signal-server/service/src/main/java/org/whispersystems/textsecuregcm/controllers/CallRoutingControllerV2.java)
  - Media Attachments: [`AttachmentControllerV4.java`](file:///D:/signalappppp/signal-server/service/src/main/java/org/whispersystems/textsecuregcm/controllers/AttachmentControllerV4.java)
  - Push Delivery: [`FcmSender.java`](file:///D:/signalappppp/signal-server/service/src/main/java/org/whispersystems/textsecuregcm/push/FcmSender.java)

### 3.3. Relational Storage Layer (MySQL 8.0)
- **Database**: `pit_messenger` on `127.0.0.1:3306`
- **Tables Initialized**:
  - Identity & Devices: `accounts`, `devices`, `phone_number_identifiers`, `used_link_device_tokens`
  - Cryptographic Keys: `signed_prekeys`, `ec_one_time_prekeys`, `paged_pq_prekeys`
  - Queued Messages: `persistent_messages`
  - User Profiles: `profiles_v1`, `profiles_v2`
  - Registration & OTP: `verification_sessions`, `allowed_registrations`

---

## 4. Reusable vs. Customization Boundary

```
+---------------------------------------------------------------------------------------+
|                                PIT MESSENGER PLATFORM                                 |
+---------------------------------------------------------------------------------------+
|  [ PRESERVED FROM SIGNAL - UNTOUCHED ]      |  [ CUSTOMIZED FOR PIT MESSENGER ]       |
|  - Double Ratchet & X3DH protocols          |  - 2Factor.in Real SMS Provider Gateway |
|  - Client SQLCipher database encryption     |  - MySQL 8 Relational Storage Engine    |
|  - RingRTC audio/video media pipelines      |  - Custom OTP Microservice & Admin API  |
|  - Signal Protobuf payload serialization   |  - Independent Coturn STUN/TURN Relays  |
|  - Pairwise forward-secret session keys     |  - Custom MinIO Attachment Object Store |
|  - Blinded token verification & signatures  |  - PIT Messenger Branding & UI Themes   |
+---------------------------------------------------------------------------------------+
```

---

## 5. Architectural Implementation Phasing

1. **Phase 1 (Current)**: Architecture Audit, End-to-End Journey Mapping, Provider Specification.
2. **Phase 2**: Real-time 2Factor.in SMS OTP Integration & Cryptographic Registration.
3. **Phase 3**: Real-time 1:1 and Group E2EE Messaging with Offline Queueing in MySQL.
4. **Phase 4**: Real Attachment Media Sharing via S3/MinIO Encrypted Storage.
5. **Phase 5**: Real-time 1:1 Voice Calling via WebRTC and Coturn Relays.
6. **Phase 6**: Real-time Video Calling and Camera/Mic Stream Controls.
7. **Phase 7**: Firebase Cloud Messaging (FCM v1) Real-time Push Delivery.
8. **Phase 8**: Production Infrastructure Staging, Hardening, and PIT Messenger Branding.
