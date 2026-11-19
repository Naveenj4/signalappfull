# ANDROID BUILD REPORT – SIGNAL CLIENT

**Repository**: `signal-android/`  
**Current Branch**: `pit-messenger-custom`  
**Base Commit**: `7c1a497356f8c99871e3db2a01d703d2980b2335`  
**Upstream Version**: Signal-Android 8.35.2 (`d507a800a61cd5819a3670fe71e84e103efa7bda`)  

---

## 1. Gradle Configuration & Variant Mapping

- **Flavor Dimensions**: `distribution` and `environment`
  - `distribution`: `play` (default), `website`
  - `environment`: `prod` (default), `staging`
- **Build Types**: `debug` (default), `release`, `spinner`, `quickstart`
- **Default Variant**: `playProdDebug`
- **Compilation Parameters**:
  - `compileSdk`: 37 (corresponds to installed SDK platform `android-37.0`)
  - `targetSdk`: 36
  - `minSdk`: 23
  - `javaVersion`: 21
  - `kotlinJvmTarget`: 21
  - `ndkVersion`: `28.0.13004108`

---

## 2. Memory Tuning for 8 GB RAM Host

Building Signal-Android requires balancing Gradle JVM and Kotlin Daemon heap limits to prevent native Out-Of-Memory (OOM) failures on Windows with 8 GB physical memory:

- `org.gradle.jvmargs`: `-Xmx2048m -Xms256m -Xss512k -XX:MaxMetaspaceSize=512m -XX:HeapBaseMinAddress=8g -XX:+UseParallelGC -Dcom.android.tools.r8.deterministicdebugging=true -XX:+UnlockExperimentalVMOptions -XX:hashCode=3`
- `kotlin.daemon.jvmargs`: `-Xmx3584m -Xms256m -Xss512k -XX:MaxMetaspaceSize=512m -XX:HeapBaseMinAddress=8g -XX:+UseParallelGC`
- `org.gradle.workers.max`: `2`
- `org.gradle.parallel`: `false`
- `org.gradle.java.home`: `C:/Program Files/Android/Android Studio/jbr` (JDK 21)
- `GRADLE_USER_HOME`: Bound to `D:\gradle-home` to prevent exhausting drive C:.

---

## 3. Verified APK Artifacts

Pre-built debug artifacts for version 8.35.2 are verified and available in the workspace:

- **Directory**: `signal-android/app/build/outputs/apk/playProd/debug/`
- **Universal Debug APK**:
  - File: `Signal-Android-play-prod-universal-debug-8.35.2.apk`
  - Size: 1,368,081,445 bytes (~1.36 GB)
- **Architecture-Specific Debug APKs**:
  - `arm64-v8a`: `Signal-Android-play-prod-arm64-v8a-debug-8.35.2.apk` (456,570,938 bytes)
  - `armeabi-v7a`: `Signal-Android-play-prod-armeabi-v7a-debug-8.35.2.apk` (428,225,208 bytes)
  - `x86_64`: `Signal-Android-play-prod-x86_64-debug-8.35.2.apk` (457,865,251 bytes)
  - `x86`: `Signal-Android-play-prod-x86-debug-8.35.2.apk` (440,054,704 bytes)

---

## 4. Execution Command for Debug Build

To execute the debug build in PowerShell on the dedicated `pit-messenger-custom` branch:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:GRADLE_USER_HOME = "D:\gradle-home"
cd "D:\signalappppp\signal-android"
.\gradlew.bat :app:assemblePlayProdDebug --stacktrace
```

---

## 5. Build Status & Assessment

- **Prerequisites Status**: Validated. SDK platforms, build tools, JDK 21, and Gradle 9.6 wrapper confirmed operational.
- **Artifact Status**: Success. Full suite of universal and architecture-specific debug APKs verified in workspace.
