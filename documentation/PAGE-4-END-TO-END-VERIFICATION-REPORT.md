# PAGE 4 – END-TO-END VERIFICATION & RUNTIME EXECUTION REPORT

**Project**: PIT Messenger  
**Date**: 2026-11-18  
**Phase**: Page 4 – Live Server Execution, MySQL 8 Integration & Dynamic OTP Verification  
**Status**: Completed & Validated  

---

## 1. Executive Summary

The PIT Messenger server stack has successfully achieved full local runtime execution, connecting the official Java/Dropwizard backend to Oracle MySQL Server 8.0 and Redis. The complete end-to-end user registration and dynamic OTP verification cycle was executed and verified live against the database.

---

## 2. Server Build & Toolchain Validation

- **Compiler**: Java 22 target executed with Oracle JDK 26 via Maven wrapper (`mvnw.cmd` 3.9.16).
- **Build Artifact**: `TextSecureServer-20261104.0.1-dirty-SNAPSHOT.jar` (Shaded Uber JAR, 461 MB).
- **Configuration Validation**:
  ```powershell
  java "-Dsecrets.bundle.filename=config\secrets.bundle.yml" -jar service\target\TextSecureServer-*.jar check config\local.yml
  # Result: io.dropwizard.core.cli.CheckCommand: Configuration is OK
  ```

---

## 3. Database & Infrastructure Topology

### 3.1 Relational Storage (MySQL 8.0.46)
- **Host & Port**: `127.0.0.1:3306` (Windows Service PID `6968`).
- **Database**: `pit_messenger` (`utf8mb4`).
- **Credentials**: `pituser` / `pitpassword!`.
- **Connection Pool**: HikariCP (`SignalMySQLPool`) initialized with 20 max connections.
- **Migration Engine**: Flyway V1–V4 migrations validated and applied.

### 3.2 Ephemeral In-Memory Storage (Redis)
- **Host & Port**: `127.0.0.1:6379` (Running from `D:\redis\redis-server.exe`).
- **Mode**: Cluster mode (`redis-signal.conf`).
- **Status**: Active (`PONG`), topology connected.

---

## 4. Live Runtime Registration & Dynamic OTP Verification

The server was launched on port `8080` and subjected to live API validation:

### Step 1: Session Creation
- **Endpoint**: `POST /v1/verification/session`
- **Request**: `{ "number": "+15559876543" }`
- **User-Agent**: `Signal-Android/8.35.2`
- **Result (200 OK)**:
  ```json
  {
    "id": "uRM7P8CF02zD1ihhLgk_Yg==",
    "nextSms": 0,
    "nextCall": null,
    "nextVerificationAttempt": 0,
    "allowedToRequestCode": true,
    "requestedInformation": [],
    "verified": false
  }
  ```

### Step 2: Code Request & Dynamic OTP Generation
- **Endpoint**: `POST /v1/verification/session/uRM7P8CF02zD1ihhLgk_Yg==/code`
- **Request**: `{ "transport": "sms", "client": "android" }`
- **Server Dispatch Log**:
  ```
  ======================================================================
  [PRODUCTION VERIFICATION CODE REQUEST]
  To:   +15559876543
  Code: 278481
  Message: "Your Signal verification code is: 278481. Do not share this code with anyone."
  Expires: 10 minutes
  ======================================================================
  ```

### Step 3: Code Verification
- **Endpoint**: `PUT /v1/verification/session/uRM7P8CF02zD1ihhLgk_Yg==/code`
- **Request**: `{ "code": "278481" }`
- **Result (200 OK)**:
  ```json
  {
    "id": "uRM7P8CF02zD1ihhLgk_Yg==",
    "nextSms": null,
    "nextCall": null,
    "nextVerificationAttempt": null,
    "allowedToRequestCode": true,
    "requestedInformation": [],
    "verified": true
  }
  ```

### Step 4: Database Persistence Confirmation
- Verified row in MySQL table `pit_messenger.verification_sessions`:
  - `session_key`: `uRM7P8CF02zD1ihhLgk_Yg==`
  - `serialized_data`: Binary session state (315 bytes)
  - `expiration`: `3589993194`

---

## 5. Client Status (Signal-Android)

- **Dedicated Branch**: `pit-messenger-custom` (commit `78371935f3`).
- **Endpoint Configuration**: `http://10.141.154.91:8080` (active host LAN IP).
- **Cleartext Security**: Configured in `network_security_config.xml`.
- **Pre-Built Debug Artifacts**: Verified at `app/build/outputs/apk/playProd/debug/`:
  - `Signal-Android-play-prod-universal-debug-8.35.2.apk` (1.36 GB)
  - `Signal-Android-play-prod-arm64-v8a-debug-8.35.2.apk` (456 MB)

---

## 6. Execution Commands

### To Start the Full PIT Messenger Server:
```powershell
cd "D:\signalappppp\signal-server"
.\scripts\start-pit-server.ps1
```

### To Start the Standalone Custom OTP Service (Port 8088):
```powershell
cd "D:\signalappppp\custom-backend"
.\start-backend.ps1
```

### To Install or Test the Android Debug APK:
```powershell
adb install -r "D:\signalappppp\signal-android\app\build\outputs\apk\playProd\debug\Signal-Android-play-prod-universal-debug-8.35.2.apk"
```

---

## 7. Compliance & Invariant Checklist

- [x] Full Dropwizard server successfully compiled and executed on Windows.
- [x] Oracle MySQL Server 8.0.46 storage layer verified with HikariCP & JDBI 3.
- [x] Redis cluster presence verified.
- [x] End-to-end registration session and OTP verification executed live.
- [x] Database state confirmed in MySQL `pit_messenger`.
- [x] Zero GitHub pushes executed.
- [x] Pristine default branches remain untouched.
- [x] All file operations and caches strictly anchored to drive `D:\`.
