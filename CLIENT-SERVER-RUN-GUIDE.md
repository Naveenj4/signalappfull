# PIT Messenger – Client and Server Run Guide

This guide covers the local Signal Java server and Android client. The Java server handles registration and messaging APIs. The separate Node.js OTP backend is optional and is not started by the main launcher.

---

## 1. Architecture & Port Overview

The local setup uses these components:

| Component | Technology | Default Port | Description |
| :--- | :--- | :--- | :--- |
| **MySQL Database** | MySQL 8.0+ | `3306` | Persistent accounts, devices, profiles, and keys (`pit_messenger`) |
| **Redis** | Redis 8.x | `6379` | Required for transient state, cache, and pub/sub |
| **Signal Server** | Java 21 (Dropwizard) | `8080` (Service)<br>`8081` (Admin/Health) | Registration and messaging API |
| **Optional OTP backend** | Node.js (Express) | `8088` | Separate test/service flow; not used by the Android app when pointed at the Java server |

---

## 2. Prerequisites

Ensure the following tools are installed on the host machine:

1. **Java JDK 21+**: Verify with `java -version`.
2. **Node.js 18+**: Verify with `node -v`.
3. **MySQL 8.0+**: Running on port `3306` with the database and credentials configured locally.
   - Set `SIGNAL_DB_HOST`, `SIGNAL_DB_PORT`, `SIGNAL_DB_NAME`, `SIGNAL_DB_USER`, and `SIGNAL_DB_PASSWORD` in the ignored `signal-server\config\pit_messenger.local.env` file.
   - Provide `signal-server\config\secrets.bundle.yml` through a local secret manager or another secure channel; the server launcher expects this file.
4. **Redis 8.x**: Running on port `6379`.

---

## 3. Quick Start (Windows – One Click)

We have provided convenient launcher batch scripts in the project root:

### To Start All Servers:
Double-click:
```bat
start_all_servers.bat
```
This script starts the Signal Java server. It checks MySQL and starts a local Redis executable if available.

### To Stop All Servers:
Double-click:
```bat
stop_all_servers.bat
```

---

## 4. Manual / Step-by-Step Server Startup

If you prefer running the Java server individually:

### Step 1: Ensure Redis is Active
```powershell
# Verify Redis is listening
netstat -ano | findstr 6379
```

### Step 2: Configure and Ensure MySQL is Active
```powershell
# Put database credentials in signal-server\config\pit_messenger.local.env.
# Do not commit this local file.
mysql -h 127.0.0.1 -u <database-user> -p -D pit_messenger -e "SELECT 1;"
```

### Step 3: Start the Signal Server (Port 8080 & 8081)
```powershell
Set-Location .\signal-server\scripts
.\start-pit-server.ps1
```
*Expected log:*
```text
==========================================================
 Starting PIT Messenger Server Stack
==========================================================
--> Loading environment config from ...\config\pit_messenger.local.env
--> Redis is active on port 6379.
--> Verifying MySQL connection at 127.0.0.1:3306...
--> MySQL credentials and database pit_messenger verified.
--> Launching TextSecureServer on port 8080 (Admin: 8081)...
...
INFO  [...]: Server: Started @...ms
```

---

## 5. Health Check & Verification

Once the servers are running, verify their status:

### Signal Server Health
```bash
curl http://127.0.0.1:8081/healthcheck
```
*Expected response contains `{"deadlocks":{"healthy":true}, ...}`*.

The optional Node.js backend also needs a local `custom-backend\.env` file with its database and OTP provider settings. It is not required for the Android app when the app is configured to use the Signal Java server.

---

## 6. Building the Android Client APK

To build the self-contained Android APK configured for this server:
1. Navigate to `signal-android`.
2. Run:
```bat
build_apk.bat
```
Output APK location:
```text
signal-android\app\build\outputs\apk\playProd\debug\Signal-Android-play-prod-universal-debug-8.35.2.apk
```

---

## 7. Connecting Android Devices over LAN / Wi-Fi

When testing from a physical phone on the same Wi-Fi:
1. Find the host computer's local IP address with `ipconfig`.
2. Set `CUSTOM_SERVER_URL` to `http://<host-ip>:8080` when building the Android app.
3. Ensure Windows Firewall permits incoming traffic on port `8080`.

Do not commit populated local environment files or `secrets.bundle.yml`.

---

## 8. Docker Deployment (Alternative for Linux / VPS)

For deploying the backend onto a cloud VPS or Docker host:
```bash
cd deployment
docker compose up -d
```
See [deployment/docker-compose.yml](./deployment/docker-compose.yml) for the container definitions.
