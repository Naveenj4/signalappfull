# GIT WORKFLOW & LOCAL DEVELOPMENT STRATEGY

**Project**: PIT Messenger  
**Policy**: Local-First Development  

---

## 1. Principles of Local Development

1. **Local-First Execution**: All features, adjustments, and test cycles are performed entirely on local branches. No commits are pushed remotely during active development.
2. **Explicit Push Policy**: Pushes to external remotes are strictly prohibited until approved by the project owner. Under no circumstances will pushes be directed to official upstream repositories (`signalapp/*`).
3. **Branch Isolation**:
   - `signal-android/`: Active work takes place on `pit-messenger-custom`. The default branches (`main` and `upstream/main`) remain pristine reference points.
   - `signal-server/`: Active work takes place on `pit-messenger-custom`. The default branch `main` (`origin/main`) remains completely unmodified.
4. **Independent Histories**: The Android client and Server backend remain independent Git repositories with separated commit histories.

---

## 2. Active Branch Architecture

### 2.1 Android Repository (`signal-android/`)
- **Active Branch**: `pit-messenger-custom`
- **Base Commit**: `7c1a497356f8c99871e3db2a01d703d2980b2335`
- **Upstream Tracking**: `upstream/main` (`d507a800a61cd5819a3670fe71e84e103efa7bda`)
- **Protected Branches**:
  - `main`: Local baseline branch.
  - `feature/securechat-privacy-group-media`: Holds unmerged historical privacy features and heap tuning commits (`78371935f3`).
  - `feature/secure-media-privacy`: Holds advanced media retention logic (`04298ebed2`).

### 2.2 Server Repository (`signal-server/`)
- **Active Branch**: `pit-messenger-custom`
- **Base Commit**: `21be84230390a80bbf33a016a12a8ef4ab9863e7` (identical to `origin/main`)
- **Protected Branches**:
  - `main`: Official Signal-Server default branch.
  - `mysql-storage`: Pre-existing experimental MySQL migration branch (`25b37f98d`).
  - `postgres-storage`: Pre-existing experimental PostgreSQL branch (`8d287e411`).
  - `baseline-dynamodb-local`: Pre-existing DynamoDB local setup branch (`494fb7e7a`).

---

## 3. Working Guidelines & Quality Gates

Before committing any change:
1. Run `git status` to inspect all staged and unstaged modifications.
2. Verify that credentials, secrets, database passwords, and personal tokens are never committed.
3. Formulate clear, descriptive commit messages specifying the subsystem and milestone:
   - Format: `<type>(<subsystem>): <imperative summary>`
   - Examples: `fix(android): resolve memory constraint in build.gradle`, `docs(server): document FoundationDB client requirement`.
4. Run regression checks or compile validation before milestone commits.
