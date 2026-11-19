# PIT Messenger – Push Notification System Report (Phase 1)

## 1. System Overview
Push notifications ensure that when PIT Messenger is closed or backgrounded, incoming messages and calls wake up the application in real time:
- **Provider**: Firebase Cloud Messaging (FCM v1 HTTP API).
- **Notification Type**: Data-only silent push notifications (`content_available: true`, `priority: high`).
- **Privacy Guarantee**: Notification payloads sent to FCM contain zero message plaintext, sender name, or call content. The payload is merely an encrypted wake-up signal containing an opaque notification token. The client wakes up, connects securely to the PIT Messenger server over WebSocket, and downloads the end-to-end encrypted payload directly.

---

## 2. Notification Dispatch Architecture

```
User A (Sender)                    PIT Server                       FCM Servers                User B (Device)
      |                                |                                 |                            |
      | 1. Sends message to User B     |                                 |                            |
      |------------------------------->| 2. Checks User B socket status  |                            |
      |                                |    User B is OFFLINE            |                            |
      |                                | 3. Queues encrypted message     |                            |
      |                                | 4. Builds FCM Data Message      |                            |
      |                                | 5. POST to FCM v1 HTTP API      |                            |
      |                                |-------------------------------->|                            |
      |                                | 6. Returns 200 OK               | 7. Delivers High-Priority  |
      |                                |<--------------------------------|    Silent Wake-up Push     |
      |                                |                                 |===========================>|
      |                                |                                 |                            | 8. App wakes up in background
      |                                | 9. Opens WebSocket Connection   |                            |
      |                                |<=============================================================|
      |                                | 10. Drains encrypted message    |                            |
      |                                |------------------------------------------------------------->|
      |                                |                                 |                            | 11. Decrypts with Double Ratchet
      |                                |                                 |                            | 12. Displays local Android notification
```

---

## 3. Required Firebase Credentials & Setup

1. **Firebase Project**:
   - Create a project in [Firebase Console](https://console.firebase.google.com) named `pit-messenger`.
   - Register package name `org.thoughtcrime.securesms` (or `com.pitmessenger.android`).
   - Download `google-services.json` and place in `signal-android/app/`.
2. **Server Service Account Key**:
   - In Firebase Console > Project Settings > Service Accounts, generate a new private key (`firebase-service-account.json`).
   - Place on server at `signal-server/config/fcm-credentials.json` (pointed to by `$env:FCM_CREDENTIALS_PATH`).

---

## 4. Key Source Code References

- **Server FCM Sender**: [`FcmSender.java`](file:///D:/signalappppp/signal-server/service/src/main/java/org/whispersystems/textsecuregcm/push/FcmSender.java)
- **Server Push Manager**: `org.whispersystems.textsecuregcm.push.PushNotificationManager`
- **Client Push Receiver**: `org.thoughtcrime.securesms.gcm.FcmReceiveService` in `Signal-Android`
- **Client Token Fetcher**: `org.thoughtcrime.securesms.gcm.FirebaseTokenFetcher`
