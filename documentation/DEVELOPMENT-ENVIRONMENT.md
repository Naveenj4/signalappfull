# DEVELOPMENT ENVIRONMENT SPECIFICATION – PIT MESSENGER

**Operating System**: Windows 11 64-bit (10.0 amd64)  
**IDE**: Google Antigravity  
**Shell**: Windows PowerShell 5.1 / PowerShell 7  

---

## 1. Hardware & Storage Topology

- **RAM**: 8 GB Physical Memory.
- **Drive Layout**:
  - `C:\`: System Drive – 236.75 GB Total, **6.17 GB Available** (Constrained).
  - `D:\`: Data Drive – 237.74 GB Total, **133.11 GB Available** (Primary Workspace).
- **Storage Strategy**:
  - All project source repositories, build artifacts, temporary caches, and dependency repositories are strictly anchored on `D:\`.
  - Gradle User Home is bound to `D:\gradle-home`.
  - Gradle temporary build directory is bound to `D:\gradle-tmp`.
  - Root project junction links `C:\PIT-Messenger` to `D:\signalappppp`.

---

## 2. Toolchains & Installed Runtimes

### 2.1 Java Development Kits (JDK)
1. **Android Studio JetBrains Runtime (JBR)**:
   - **Path**: `C:\Program Files\Android\Android Studio\jbr`
   - **Version**: OpenJDK 21.0.10 (JetBrains s.r.o. 21.0.10+-14961533-b1163.108)
   - **Usage**: Used as `JAVA_HOME` for Signal-Android builds (`javaVersion = "21"`, AGP 9.4/9.6).
2. **Oracle JDK 26**:
   - **Path**: `C:\Program Files\Java\jdk-26.0.1`
   - **Version**: Java 26.0.1 (Oracle Corporation)
   - **Usage**: System default JDK on PATH. Compatible with Maven and Java 22+ compile targets.

### 2.2 Git Version Control
- **Version**: Git 2.53.0.windows.2
- **Path**: `C:\Program Files\Git\cmd\git.exe`

### 2.3 Android SDK & Platform Tools
- **SDK Root**: `C:\Users\Naveen\AppData\Local\Android\Sdk`
- **Installed Build Tools**:
  - `34.0.0`, `35.0.0`, `36.0.0`, `36.1.0`, `37.0.0`
- **Installed Platforms**:
  - `android-34`, `android-36`, `android-36.1`, `android-37.0` (Matches `compileSdk = 37`)
- **NDK Status**:
  - Not present under `Sdk\ndk`. Android build configuration references NDK `28.0.13004108`.

### 2.4 Build Wrappers
- **Gradle Wrapper**: Gradle 9.6.0 (located in `signal-android/gradlew.bat`)
- **Maven Wrapper**: Apache Maven 3.9.16 (located in `signal-server/mvnw.cmd`)

### 2.5 Database & Services
- **MySQL**: XAMPP MySQL available locally on Windows.
- **Redis**: Redis directory detected at `D:\redis`.
- **WSL/Linux Environment**: WSL Ubuntu image available at `D:\WSL_Ubuntu`.
