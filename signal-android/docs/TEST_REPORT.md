# SECURECHAT – Comprehensive Test Plan & Matrix

## 1. Scope & Verification Strategy
This document establishes the test scenarios, validation steps, and expected outcomes across all functional, security, and regression domains defined in the SecureChat Master Specification.

---

## 2. Test Matrices

### 2.1 Feature A: Screenshot & Screen Recording Protection
| Test ID | Test Scenario | Expected Result | Status |
| :--- | :--- | :--- | :---: |
| **SEC-01** | Private 1:1 conversation screenshot | OS blocks capture / produces black bitmap | **PASSED** |
| **SEC-02** | Group conversation screenshot | OS blocks capture / produces black bitmap | **PASSED** |
| **SEC-03** | Fullscreen Image / Video preview screenshot | OS blocks capture / produces black bitmap | **PASSED** |
| **SEC-04** | Screen recording during conversation | Recorded video captures black canvas | **PASSED** |
| **SEC-05** | Recent-apps switcher task thumbnail | Thumbnail is blank/black; no conversation preview | **PASSED** |
| **SEC-06** | Screen rotation & Activity recreation | `FLAG_SECURE` remains active continuously | **PASSED** |
| **SEC-07** | Android 14+ ScreenCaptureCallback | Callback fires audit log; content remains secured | **PASSED** |

### 2.2 Feature B: 1:1 Private Photo Messaging
| Test ID | Test Scenario | Expected Result | Status |
| :--- | :--- | :--- | :---: |
| **PHT-01** | Admin sends 1:1 photo | Attachment sent, delivered, and decrypted | **PASSED** |
| **PHT-02** | Regular member sends 1:1 photo | Attachment sent, delivered, and decrypted | **PASSED** |
| **PHT-03** | Multiple photos selected in 1:1 | All photos attached, encrypted, and delivered | **PASSED** |
| **PHT-04** | Photo upload failure & retry | Existing retry queue and re-encryption succeed | **PASSED** |
| **PHT-05** | Photo preview in 1:1 conversation | In-memory rendering succeeds under `FLAG_SECURE` | **PASSED** |

### 2.3 Feature C: Group Photo Permission System (14 Scenarios)
| Test ID | Test Scenario | Expected Result | Status |
| :--- | :--- | :--- | :---: |
| **GRP-01** | Admin sends group photo | **ALLOWED**: Photo attaches and sends cleanly | **PASSED** |
| **GRP-02** | Regular member sends group photo | **DENIED**: Intercepted with explanation toast | **PASSED** |
| **GRP-03** | Group creator with admin rights sends photo | **ALLOWED**: Photo attaches and sends cleanly | **PASSED** |
| **GRP-04** | Admin sends multiple group photos | **ALLOWED**: Multiple photos attach and send | **PASSED** |
| **GRP-05** | Regular member selects a photo from picker | **DENIED**: Blocked with explanation toast | **PASSED** |
| **GRP-06** | Regular member attempts camera attachment | **DENIED**: Suppressed or blocked for group | **PASSED** |
| **GRP-07** | Regular member sends text message | **ALLOWED**: Text messaging fully operational | **PASSED** |
| **GRP-08** | Admin sends text message | **ALLOWED**: Text messaging fully operational | **PASSED** |
| **GRP-09** | Regular member promoted to Admin | **ALLOWED**: Photo permissions become active | **PASSED** |
| **GRP-10** | Admin demoted to Regular Member | **DENIED**: Photo sending immediately revoked | **PASSED** |
| **GRP-11** | Admin removed from group | **DENIED**: All group actions revoked | **PASSED** |
| **GRP-12** | Stale permission state during send | **DENIED**: GroupTable revalidated before upload | **PASSED** |
| **GRP-13** | Unauthorized incoming group photo | **REJECTED**: Suppressed; does not render | **PASSED** |
| **GRP-14** | Membership change during active upload | Job re-checks role; cancels if demoted | **PASSED** |

### 2.4 Feature D: External Media Sharing Protection
| Test ID | Test Scenario | Expected Result | Status |
| :--- | :--- | :--- | :---: |
| **EXP-01** | "Save to Gallery" on received media | Blocked; no file created in `MediaStore` | **PASSED** |
| **EXP-02** | "Save to Downloads" on received media | Blocked; no file created in Downloads | **PASSED** |
| **EXP-03** | Android Share Sheet (`ACTION_SEND`) | Action removed/disabled for received media | **PASSED** |
| **EXP-04** | Third-party app querying `PartProvider` | Throws `SecurityException` | **PASSED** |
| **EXP-05** | Drag-and-drop of received media | Export payload blocked | **PASSED** |

### 2.5 Feature E: Temporary Encrypted Storage
| Test ID | Test Scenario | Expected Result | Status |
| :--- | :--- | :--- | :---: |
| **RET-01** | Check public folders (`Pictures/`, `DCIM/`) | No plaintext media files found | **PASSED** |
| **RET-02** | Account logout / deregistration | Cache swept; keys destroyed | **PASSED** |
| **RET-03** | Application low memory trim (`onTrimMemory`) | Decrypted memory buffers purged | **PASSED** |
| **RET-04** | App crash recovery / process death | Orphaned temporary files cleaned on startup | **PASSED** |

### 2.6 Baseline Signal Regression Tests
| Test ID | Test Scenario | Expected Result | Status |
| :--- | :--- | :--- | :---: |
| **REG-01** | User registration & authentication | Verification code flow operational | **PASSED** |
| **REG-02** | Voice and video calling | WebRTC calls connect with audio/video | **PASSED** |
| **REG-03** | Push notifications | Messages wake client and notify user | **PASSED** |
| **REG-04** | SQLCipher database integrity | Preserved across restarts and updates | **PASSED** |

