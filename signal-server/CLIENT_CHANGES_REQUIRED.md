# Client Changes Required (CLIENT_CHANGES_REQUIRED.md)

This document specifies every change required in the **Signal-Android** and **Signal-Desktop** client codebases to connect cleanly to this private self-hosted server.

---

## 1. Signal-Android Modifications

Target Release: **Signal-Android `v7.24.x`** (or `v7.22.x` – `v7.25.x`).  
Repository: `https://github.com/signalapp/Signal-Android`

### 1.1 `app/build.gradle.kts` Endpoint & Key Replacements

Search for `buildConfigField` in `app/build.gradle.kts` and update the following values:

```kotlin
// 1. Service Base URLs
buildConfigField("String", "SIGNAL_URL", "\"https://api.yourdomain.com\"")
buildConfigField("String", "SIGNAL_CDN_URL", "\"https://cdn.yourdomain.com\"")
buildConfigField("String", "SIGNAL_CDN2_URL", "\"https://cdn.yourdomain.com\"")
buildConfigField("String", "SIGNAL_CDN3_URL", "\"https://cdn.yourdomain.com\"")

// 2. Disabled Cloud / Proprietary Microservices
// Point to main API (which returns safe 200/empty responses) or empty string:
buildConfigField("String", "SIGNAL_CDSI_URL", "\"https://api.yourdomain.com\"")
buildConfigField("String", "SIGNAL_SFU_URL", "\"https://api.yourdomain.com\"")
buildConfigField("String", "SIGNAL_SVR_URL", "\"https://api.yourdomain.com\"")
buildConfigField("String", "SIGNAL_SVR2_URL", "\"https://api.yourdomain.com\"")
buildConfigField("String", "STORAGE_URL", "\"https://api.yourdomain.com\"")

// 3. Cryptographic Trust Roots (from CLIENT_KEYS.md)
buildConfigField("String", "UNIDENTIFIED_SENDER_TRUST_ROOT", 
    "\"BQC0E3gY+lq9bW237g54f8m3v05Z2KxYv...\"") // Base64 public key from CLIENT_KEYS.md
buildConfigField("String", "ZKGROUP_SERVER_PUBLIC_PARAMS", 
    "\"0a24080112204c3d827f3b89b88cf18146747b74ba5a3006bbd121bc6eb58f4d9b4b045e7552\"")
```

---

### 1.2 Certificate Pinning & Trust Store

Official Signal-Android pins Whisper Systems certificates. For your custom server:

1. **Option A: Let's Encrypt (Standard):**
   - If using Let's Encrypt on your VPS, comment out certificate pinning in:
     `libsignal/service/src/main/java/org/whispersystems/signalservice/internal/push/PushServiceSocket.java`
     (or `NetworkModule.kt` / `SignalServiceNetworkAccess.java`).
   - Use standard Android system CA trust store (which trusts ISRG Root X1 out of the box on Android 7.1+).
2. **Option B: Custom Internal CA:**
   - Place your custom CA root certificate in `app/src/main/res/raw/whisper.store`.

---

### 1.3 Firebase Push Configuration (FCM)

1. In the Google Firebase Console (project `signalapp-84c12`):
   - Add your Android application package name (e.g. `org.thoughtcrime.securesms` or your custom `com.company.messenger`).
   - Download the generated `google-services.json`.
2. Place `google-services.json` into:
   `Signal-Android/app/google-services.json`
3. If building without Google Play Services (WebSocket-only):
   - Build using the FOSS variant:
     ```bash
     ./gradlew assembleFossRelease
     ```
   - In the app settings on the device, disable Android battery optimization for Signal ("Don't optimize") so Android does not kill the persistent background WebSocket.

---

### 1.4 Features to Disable or Hide in Client UI

| Feature | Reason | How to Disable in Client |
| :--- | :--- | :--- |
| **Group Calling (SFU)** | Requires proprietary Calling Service server | In `SignalStore.settings()`, disable group calling flag or hide call icon in group headers. 1:1 calling works normally. |
| **Payments / MobileCoin** | Internal messenger has no crypto payments | Set `TextSecurePreferences.isPaymentsEnabled = false` or compile with payments disabled flag. |
| **Signal Boost / Donations / Badges** | No subscription billing infrastructure | Hide "Donate to Signal" menu item in `SettingsFragment.java`. |
| **Contact Discovery (CDSI)** | Intel SGX hardware enclave not available | In `ContactDiscovery.java`, skip automatic address book sync. Users search and chat by typing the phone number directly. |
| **Cloud PIN / SVR Backup** | SGX Secure Value Recovery not available | Make PIN prompt optional during onboarding. Local passphrase remains available. |
| **Stories** | Ephemeral social media stories | Disable in `app/src/main/res/values/config.xml` (`<bool name="stories_enabled">false</bool>`). |

---

## 2. Signal-Desktop Modifications

Repository: `https://github.com/signalapp/Signal-Desktop`

### 2.1 Configuration File Updates
In `config/production.json` (or `ts/environment.ts`):

```json
{
  "serverUrl": "https://api.yourdomain.com",
  "cdnUrl": "https://cdn.yourdomain.com",
  "storageUrl": "https://api.yourdomain.com",
  "directoryUrl": "https://api.yourdomain.com",
  "attachmentsUrl": "https://cdn.yourdomain.com",
  "unidentifiedSenderTrustRoot": "REPLACE_WITH_HEX_TRUST_ROOT_FROM_CLIENT_KEYS"
}
```

### 2.2 Device Linking (QR Code)
- Secondary desktop linking works seamlessly out-of-the-box.
- When opening Signal-Desktop, scan the generated QR code using your registered Signal-Android phone (**Settings -> Linked Devices -> Link New Device**).
- The phone transmits the encrypted identity keys to the desktop via the server's `ProvisioningController` (`/v1/devices/provisioning`).

---

## 3. Server Response Compatibility Matrix

Every endpoint called by the client that is not backed by proprietary cloud microservices has been adapted to return safe minimal responses:

| Client Request Endpoint | Server Component | Self-Hosted Behavior | Client Impact |
| :--- | :--- | :--- | :--- |
| `POST /v1/verification/sessions` | `VerificationController` | Returns local session ID | **Works 100%** |
| `POST /v1/verification/sessions/{id}/code` | `StubRegistrationServiceClient` | Generates 6-digit OTP | **Works 100%** (zero SMS fee) |
| `PUT /v1/verification/sessions/{id}/code` | `CompanyVerificationManager` | Verifies & burns OTP | **Works 100%** |
| `POST /v1/accounts` | `AccountController` | Inserts into MySQL `accounts` | **Works 100%** |
| `GET /v1/devices/provisioning` | `ProvisioningController` | Redis pub/sub QR device link | **Works 100%** (Desktop syncs) |
| `GET /v2/calling/relays` | `CallRoutingControllerV2` | Hands out coturn ICE credentials | **Works 100%** (1:1 voice/video) |
| `GET /v1/config/` | `RemoteConfigController` | Returns MySQL remote configs | **Works 100%** |
| `GET /v1/storage/` | `StorageController` | Returns empty / safe 200 OK | App uses local storage |
| `GET /v1/badges/` | `ProfileController` | Returns empty list | Donation badges hidden |
| `POST /v1/payments/` | `PaymentsController` | Returns 404/disabled | Crypto payments disabled |
