# PIT Messenger – End-to-End Testing & Verification Report (Phase 1)

## 1. Feature Status Summary Matrix

| Subsystem / Feature | Implementation Status | Test Methodology | Verified Milestone / Evidence | Next Verification Step |
|---|---|---|---|---|
| **Local Workspace & Repo Layout** | **IMPLEMENTED** | Local Directory Junctions & Git inspection | `D:\signalappppp\` with `pit-messenger-custom` branches on both repos | Retain local isolation |
| **Relational Storage (MySQL 8.0)** | **IMPLEMENTED** | Schema execution & automated test queries | 12 tables created, verified connectivity with user `pituser` | Persist live schema migrations |
| **In-Memory Cache (Redis 5.0)** | **IMPLEMENTED** | Daemon execution & TCP socket test | Redis listening on `127.0.0.1:6379`, `PONG` verified | Production Redis 7 configuration |
| **Server Packaging (Dropwizard)** | **IMPLEMENTED** | Maven offline compilation & JAR execution | Fat JAR `TextSecureServer-20261104.0.1-dirty-SNAPSHOT.jar` (461MB) | Automated systemd service |
| **Android Universal APK Build** | **IMPLEMENTED** | Gradle `assemblePlayProdDebug` | APK generated at `Signal-Android-play-prod-universal-debug-8.35.2.apk` | Build brand-updated APK |
| **OTP Verification (2Factor.in)** | **ANALYSIS COMPLETE** | API specification audit & schema preparation | `OTP-INTEGRATION-REPORT.md` and `SMS-PROVIDER-SETUP.md` created | Phase 2 live SMS dispatch test |
| **Device Registration & PreKeys** | **INTEGRATION TESTED**| Live HTTP API integration test | `POST /v1/verification/session` and verification code verification tested | Two-device registration |
| **E2EE Messaging (1:1 & Group)** | **ANALYSIS COMPLETE** | MessageController audit & MySQL queue table | `persistent_messages` table provisioned and tested in MySQL | Live message exchange test |
| **Voice Calling (1:1 RingRTC)** | **ANALYSIS COMPLETE** | CallingRelaysController & WebRTC audit | `GetCallingRelaysResponse.java` and `WebRtcCallManager` audited | Coturn relay deployment & test |
| **Video Calling (Camera/Mic)** | **ANALYSIS COMPLETE** | Video stream negotiation & SDP audit | WebRTC video pipeline in Signal-Android validated | Physical device camera test |
| **Push Notifications (FCM v1)** | **ANALYSIS COMPLETE** | FcmSender & FCM v1 protocol audit | `FcmSender.java` inspected with `FCM_CREDENTIALS_PATH` | Deploy Firebase service account |

---

## 2. Real-Device Physical Test Plan (Phase 2 & Beyond)

| Test Case | Scenario | Expected Result | Pass Criteria |
|---|---|---|---|
| **TC-REG-01** | Real SMS Request via 2Factor.in | 2Factor API returns `Status: Success` | Actual SMS received on user's phone within 15 seconds |
| **TC-REG-02** | Valid OTP Submission | Backend validates hash, session marked `verified: true` | Android app transitions to Profile Setup screen |
| **TC-REG-03** | Invalid OTP Submission | Backend increments attempt counter to 1/5 | UI shows "Invalid verification code. 4 attempts remaining." |
| **TC-REG-04** | Expired OTP Submission (after 10 min) | Backend returns HTTP 400 Expired | UI prompts user to request a new code |
| **TC-REG-05** | Replay of Used OTP | Backend rejects already consumed code | HTTP 400 Bad Request, registration blocked |
| **TC-MSG-01** | Real-Time 1:1 Message (Both Online) | Message sent over WebSocket directly | Recipient displays message and plays incoming chime immediately |
| **TC-MSG-02** | Offline Message Queueing | Sender delivers ciphertext to server while recipient phone is in Airplane Mode | Message stored in `persistent_messages` in MySQL |
| **TC-MSG-03** | Offline Recovery | Recipient exits Airplane Mode and reconnects | Queue drained, message displayed, delivery ACK returned to sender |
| **TC-CALL-01** | 1:1 Voice Call on Local LAN | Direct P2P WebRTC audio stream established | Clear bidirectional audio with <50ms latency |
| **TC-CALL-02** | 1:1 Voice Call Across Cellular NAT | Coturn STUN/TURN relays audio packet streams | Bidirectional audio connects even behind strict cellular NAT |
| **TC-CALL-03** | 1:1 Video Call | Camera and microphone streams active | Video renders smoothly on both front/back cameras |
