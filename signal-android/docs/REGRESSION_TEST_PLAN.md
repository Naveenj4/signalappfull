# Signal Android – Comprehensive Regression & Privacy Test Plan

## 1. Test Objectives & Scope
The test suite verifies the core privacy enhancements while preserving existing Signal messaging capabilities:
1. **Zero Regression**: Group messaging, calling, registration, pre-key exchange, notification delivery, and audio/document attachments remain operational.
2. **Video & Screen Recording Protection**: Normal screenshots, task switcher previews, and video screen recordings produce blank/black frames.
3. **Group-Only Photo Sharing Policy**: Senders can attach and send photos in group conversations, but photo sends in 1:1 person-to-person chats are blocked.
4. **Export Restriction & Storage Isolation**: Received media cannot be exported via share sheets, saved to `MediaStore`, or accessed by foreign applications.

---

## 2. Test Execution Matrix

### Matrix A: Screenshot & Video Screen Recording Protection
| Test ID | Test Scenario | Steps | Expected Outcome |
| :--- | :--- | :--- | :--- |
| **SCR-01** | Standard 1:1 Conversation Capture | Open active 1:1 chat; press Power + Vol Down (or `adb shell screencap`). | Screenshot blocked; OS toast "Can't take screenshot due to security policy" or black image generated. |
| **SCR-02** | Group Conversation Capture | Open active Group chat; attempt screenshot and screen recording. | Screen recording produces black video frames for application window. |
| **SCR-03** | Video Playback Recording | Play received video in `VideoPlayer` / `MediaViewerActivity`; run screen recorder. | Video surface (`SurfaceView.setSecure(true)`) renders as pure black in recording. |
| **SCR-04** | Recent Apps Switcher (Task Preview) | Open app, switch to Android Recent Apps overview. | App task snapshot is blank/obscured with app icon; no chat or media visible. |
| **SCR-05** | Configuration Change / Screen Rotation | Rotate device while in chat or media viewer; attempt screenshot. | `FLAG_SECURE` remains active post-recreation without interruption. |
| **SCR-06** | Sub-Dialog & Bottom Sheet Windows | Open attachment sheet, emoji picker, or confirmation dialogs; attempt capture. | Sub-windows inherit `FLAG_SECURE` and cannot be captured. |

### Matrix B: Group-Only Photo Sharing & 1:1 Restriction
| Test ID | Test Scenario | Steps | Expected Outcome |
| :--- | :--- | :--- | :--- |
| **PHO-01** | Photo Send in Group Chat | In an active Group conversation, tap Attachment -> Gallery or Camera -> Select photo -> Send. | Photo attaches, previews, encrypts, and delivers successfully to all group members. |
| **PHO-02** | Photo Send in 1:1 Chat (Gallery) | In a 1:1 chat, tap Attachment -> Gallery. | Blocked with toast: "Photos can only be shared in groups." |
| **PHO-03** | Quick Camera in 1:1 Chat | In a 1:1 chat, tap the camera icon in the compose bar. | Blocked with toast: "Photos can only be shared in groups." |
| **PHO-04** | Keyboard Image / GIF in 1:1 Chat | In a 1:1 chat, insert an image or sticker from system keyboard. | Blocked with toast: "Photos can only be shared in groups." |
| **PHO-05** | Non-Photo Attachments in 1:1 Chat | In a 1:1 chat, attach a document (PDF) or voice note and send. | Non-photo attachments send and deliver normally in 1:1 chat. |

### Matrix C: External Media Export Restriction
| Test ID | Test Scenario | Steps | Expected Outcome |
| :--- | :--- | :--- | :--- |
| **EXP-01** | Save Received Photo to Gallery | In 1:1 or group chat, long press received photo or open in viewer; look for "Save" action. | "Save" action is hidden or blocked by `MediaExportPolicy`. No file is added to `MediaStore` or `Pictures/`. |
| **EXP-02** | External App Share (Share Sheet) | In media viewer, check for external share action. | Share to external apps is disabled/blocked for received media. |
| **EXP-03** | In-App Message Forwarding | Long press received media, tap "Forward" to another Signal group. | Forwarding completes successfully inside Signal (internal database reference, no external export). |
| **EXP-04** | ContentProvider Direct Access | Attempt to query `content://org.thoughtcrime.securesms.part/part/<id>` from external test app. | `SecurityException` thrown by `PartProvider`; external package cannot read stream. |
| **EXP-05** | Text Sharing Preservation | Tap "Invite friends" or share an invite link. | Standard Android share sheet opens normally for non-media text content. |

### Matrix D: Local Storage & Retention Integrity
| Test ID | Test Scenario | Steps | Expected Outcome |
| :--- | :--- | :--- | :--- |
| **STO-01** | Inspection of Shared Storage | Receive multiple photos and videos; browse device using Android Files app. | No Signal media files appear in `/sdcard/DCIM`, `/sdcard/Pictures`, or `/sdcard/Download`. |
| **STO-02** | Media Scanner Verification | Run `MediaStore` query for recently added media. | No rows inserted by Signal for received attachments. |
| **STO-03** | App-Private Encryption Verification | Inspect `/data/data/org.thoughtcrime.securesms/app_parts/`. | Files are encrypted byte blobs (`ModernDecryptingPartInputStream`), unreadable without master key. |
| **STO-04** | Account Deletion / Logout Cleanup | Trigger account deletion or clear app data. | `MediaRetentionManager` purges app-private parts and transient caches cleanly. |

---

## 3. Automated Unit Test Suite
1. **`BaseActivitySecurityTest`**:
   - Validates that `BaseActivity` applies `FLAG_SECURE` on window initialization and resume.
   - Validates that temporary security holds do not compromise global screen security.
2. **`MediaExportPolicyTest`**:
   - Validates that `isPhotoSendAllowed(isGroup = true)` is `true`.
   - Validates that `isPhotoSendAllowed(isGroup = false)` is `false`.
   - Validates that received media save/share operations are rejected.
   - Validates that foreign calling packages are denied in `isExternalPackageAccessAllowed`.
3. **`MediaRetentionManagerTest`**:
   - Validates creation of `.nomedia` transient cache directory.
   - Validates cleaning of transient media files on lifecycle events and logout.
