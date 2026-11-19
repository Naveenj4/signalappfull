# SERVER BUILD REPORT – SIGNAL SERVER

**Repository**: `signal-server/`  
**Current Branch**: `pit-messenger-custom`  
**Base Commit**: `21be84230390a80bbf33a016a12a8ef4ab9863e7` (Matches upstream `origin/main`)  

---

## 1. Maven & Module Configuration

- **Build Tool**: Apache Maven 3.9.16 via `mvnw.cmd` wrapper.
- **Compiler Level**: Java 22 (`<java.release.version>22</java.release.version>`).
- **Core Modules**:
  1. `service`: Main Dropwizard server application, WebSocket resources, and API endpoints.
  2. `websocket-resources`: WebSocket handling, framing, and bidirectional protocols.
  3. `api-doc`: OpenAPI and documentation generation module.
  4. `integration-tests`: Testcontainers-based integration testing suite.
  5. `spam-filter`: Supplementary spam filtering profile module.

---

## 2. Infrastructure Dependencies

The original Signal-Server architecture requires several external subsystems:

1. **FoundationDB (FDB)**:
   - Version: `7.3.76` (API version `730`).
   - Requires native C client library (`libfdb_c.so` on Linux, `fdb_c.dll` on Windows).
2. **Redis**:
   - Container Image: `redis:7.4-alpine` and `redis-cluster:7.4.3`.
   - Used for presence, ephemerality, and pub/sub message dispatch.
3. **AWS DynamoDB & S3**:
   - Original store for accounts, devices, pre-keys, and encrypted attachments.

---

## 3. Windows Compatibility & Assessment

- **Maven Wrapper**: Operational. Executing `.\mvnw.cmd --version` on Windows succeeds cleanly with Apache Maven 3.9.16 and Java 26.0.1.
- **Native Runtime Constraints**:
  - The FoundationDB Java client loads native shared libraries (`fdb_c`). The official FoundationDB packages are primarily built for Linux x86_64.
  - Server start scripts (`scripts/*.sh`) are POSIX shell scripts.
  - Integration tests rely on Testcontainers communicating with a Docker daemon (Docker Desktop or WSL2 engine).
- **Execution Strategy for Windows**:
  - While Maven wrapper compilation can be initiated on Windows, running the complete server stack is optimal within WSL2 Ubuntu (located at `D:\WSL_Ubuntu`) or via Docker Compose on drive `D:\`.
  - The server should not be assumed to run standalone without external databases and caches initialized.

---

## 4. Execution Commands

To verify compilation via the Maven wrapper:

```powershell
cd "D:\signalappppp\signal-server"
.\mvnw.cmd clean compile -DskipTests
```
