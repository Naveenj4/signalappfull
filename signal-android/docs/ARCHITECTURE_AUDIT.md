# SECURECHAT – Architectural Audit & Baseline Inventory

## 1. Executive Summary & Repository Scope
This document provides the foundational engineering audit of the Signal Android codebase (`Signal-Android` v8.35.2) in preparation for the SecureChat privacy and group media security enhancements. 

The audit covers:
- System module boundaries, dependency injection, and application lifecycle.
- 1:1 and group messaging pipelines under the Signal Protocol (Double Ratchet, PreKeys, Sender Keys).
- Group permission, membership, and administrator role hierarchy (`GroupsV2`).
- Attachment selection, staging, encryption, CDN upload, and on-demand rendering.
- Screenshot, screen recording, and hardware surface protections.
- External media export entry points (Share sheets, MediaStore, ContentProviders).
- Storage architecture, SQLCipher database schemas, and ephemeral cache lifecycle.

---

## 2. Repository Module Structure & Application Lifecycles

### 2.1 Multi-Module Breakdown
Signal Android is structured into isolated functional modules:
- `:app`: Main client application containing UI, ViewModels, SQLCipher databases, background jobs, and application lifecycle coordinators.
- `:core:ui`: Common UI foundations, base themes, window extensions (`WindowExtensions.kt`), and dialog scaffolding.
- `:core:util`: Core utilities including logging (`Log.java`), stream utilities, and threading primitives.
- `:core:models`: Shared cross-module models (`RecipientId`, `AttachmentId`, `ThreadId`).
- `:feature:media-send`: Media selection, photo picker invocation, camera capture, image editor, and attachment preparation.
- `:feature:media-viewer`: Standalone and conversational media preview and viewing components.
- `:feature:registration`: Onboarding, phone verification, PIN validation, and account recovery.
- `:lib:video`: Video streaming, ExoPlayer wrapper, and transcoding tools.
- `:lib:signal-service`: Network protocol, cryptographic operations, and CDN transport.

### 2.2 Application Lifecycle and Key Singletons
- **`ApplicationContext`** (`org.thoughtcrime.securesms.ApplicationContext`): Root `Application` class. Configures:
  - `SignalStore`: Shared encrypted preference and key-value storage.
  - `AppDependencies`: Dependency injection graph for database accessors, job managers, and protocol clients.
  - `KeyCachingService`: In-memory master key holder.
  - System memory trim observers (`onTrimMemory`, `onLowMemory`).
- **`BaseActivity`** (`org.thoughtcrime.securesms.BaseActivity`): Inherited by all primary UI activities (`MainActivity`, `ConversationActivity`, `MediaViewerActivity`). Controls window flags, dynamic themes, and screenshot security.

---

## 3. Messaging Implementation: 1:1 vs Group Conversations

### 3.1 One-to-One (1:1) Messaging Architecture
- **Protocol**: Signal Protocol using Double Ratchet, Curve25519, AES-256-CBC/GCM, and HMAC-SHA256.
- **Delivery Flow**:
  1. User selects recipient and composes text or attaches photos.
  2. Outgoing message is staged in local database (`MessageTable`, `MmsDatabase`).
  3. `AttachmentUploadJob` encrypts attachments with unique AES keys and streams to CDN.
  4. Message envelope containing ciphertext and attachment pointers is dispatched via WebSocket or REST (`PushSendJob`).
  5. Receiving party fetches envelope, decrypts ratchet layer, and queues `AttachmentDownloadJob`.
- **Policy Scope**: Master prompt requires **1:1 photo messaging to remain fully preserved and operational**. Both admins and non-admins must be able to send and receive photos in 1:1 chats without restriction.

### 3.2 Group (GroupsV2) Messaging Architecture
- **Protocol**: GroupsV2 protocol backed by client-side Sender Keys.
- **Group Master Key & Secret**: Group state is encrypted under a group-specific secret key derived from `GroupMasterKey`.
- **Message Dispatch**:
  - Sender encrypts message once with their `SenderKey`.
  - Message is fanned out to group members via server.
  - Attachments are encrypted once with an ephemeral symmetric key and uploaded to CDN; decryption key is distributed inside the encrypted group message.

---

## 4. Group Permission & Role Hierarchy Audit

### 4.1 Existing Group Roles in Codebase
Signal GroupsV2 maintains member roles in `GroupTable.kt` and `GroupRecord.kt`:
```kotlin
enum class MemberLevel(val isInGroup: Boolean) {
  NOT_A_MEMBER(false),
  PENDING_MEMBER(false),
  REQUESTING_MEMBER(false),
  FULL_MEMBER(true),
  ADMINISTRATOR(true)
}
```

### 4.2 Group Role Verification APIs
In `GroupRecord.kt`:
- `fun isAdmin(recipient: Recipient): Boolean`: Evaluates whether the given recipient is an administrator of the group.
- `fun memberLevel(recipient: Recipient): GroupTable.MemberLevel`: Returns exact membership state.
- `fun isMember(recipient: Recipient): Boolean`: Returns true if active full member or administrator.
- `fun isPendingMember(recipient: Recipient): Boolean`: Returns true for pending invites.

### 4.3 Group Access Control
- `GroupAccessControl`: Manages permissions for editing group info, adding members, and announcements.
- Existing groups support announcement mode (where only admins can send messages). SecureChat extends this concept to **Admin-Only Group Photo Attachments**, where regular members retain full text/voice messaging rights but are strictly barred from attaching photos.

---

## 5. Attachment Pipelines: Selection, Staging, Upload & Rendering

### 5.1 Selection & Staging
- Initiated in `ConversationFragment.kt` via:
  1. Quick Camera button.
  2. Attachment menu (Gallery / Photo picker).
  3. Keyboard gallery / photo paste.
- Media picker uses modern Android Photo Picker (`ActivityResultContracts.PickVisualMedia` / `PickMultipleVisualMedia`), avoiding broad `READ_EXTERNAL_STORAGE` permissions.
- Selected media streams are staged as app-private blobs in `BlobProvider`.

### 5.2 Upload Workflow
- `AttachmentUploadJob` reads from `BlobProvider`.
- Encrypts payload with an ephemeral AES-256 key.
- Uploads ciphertext to CDN.
- Returns attachment metadata (id, digest, key, size, blurhash) to be embedded in `OutgoingMessage`.

### 5.3 Download & Rendering Workflow
- `AttachmentDownloadJob` fetches ciphertext from CDN.
- Re-encrypts ciphertext at rest using app-specific `AttachmentSecret` into private `app_parts/` directory.
- `PartAuthority.getAttachmentStream()` decrypts parts on-the-fly directly into memory buffers.
- Rendered via Glide for images and ExoPlayer for videos.

---

## 6. Screenshot & Screen Recording Protection Audit

### 6.1 Upstream Implementation
- Upstream uses `WindowUtil.initializeScreenshotSecurity(Activity, Window)`.
- Enabled conditionally based on `SignalStore.settings.isScreenSecurityEnabled` (defaults to false).
- When false, Android takes task snapshots for the recent-apps switcher, exposing conversations in the task tray.

### 6.2 Target Hardening
- Universal enforcement via `PrivacyProtectionManager.kt`:
  - Enforce `FLAG_SECURE` unconditionally on all conversation windows, media viewers, preview dialogs, and story viewers.
  - Android 14+ `ScreenCaptureCallback` integration for proactive audit logging.
  - Hardware surface protection: `SurfaceView.setSecure(true)` on ExoPlayer video rendering surfaces to defeat screen recorders and external compositors.

---

## 7. External Media Sharing & Export Mechanisms Audit

### 7.1 Existing Export Vectors in Signal Android
1. **Save to Gallery / Storage**:
   - `SaveAttachmentUtil.kt`: Writes received decrypted media to Android `MediaStore.Images.Media` / `MediaStore.Downloads`.
   - `AttachmentSaver.kt`: Background worker writing parts to public storage.
2. **External Application Share Sheet**:
   - `MediaViewerActivity.kt` & `MediaPreviewFragment.kt`: Builds `ACTION_SEND` intent with `PartAuthority.getAttachmentPublicUri(uri)`.
3. **ContentProvider Exposure**:
   - `PartProvider.java` (`${applicationId}.part`): Returns decrypted attachment streams to external apps when granted URI permissions.
4. **Stories Context Menu**:
   - `StoryContextMenu.kt`: Allows saving and sharing received stories.

### 7.2 Required Restrictions
- All above vectors must be intercepted, disabled, or rejected with `SecurityException` for received media.
- Users can select their own local photos to send, but cannot export received protected media.

---

## 8. Database Architecture & Ephemeral Media Storage

### 8.1 Database Storage
- SQLCipher encrypted SQLite database (`SignalDatabase`).
- Attachment metadata stored in `AttachmentTable`.
- Ciphertext stored in app-private directory (`/data/data/org.thoughtcrime.securesms/app_parts`).

### 8.2 Ephemeral Cache Hardening
- Plaintext media must never reside in shared storage (`/sdcard/`, `Pictures/`, `Downloads/`).
- Cached thumbnails and temporary decrypted views must reside strictly in app-private `.nomedia` directories.
- Lifecycle purge hooks:
  - Account logout / deregistration.
  - Application memory trim (`onTrimMemory(TRIM_MEMORY_COMPLETE)`).
  - Explicit media retention time window expiration.

---

## 9. Baseline Build & Compatibility Verification
- Upstream Build Variant: `playProdDebug`.
- Java 21 / Android Studio JBR.
- Android Compile SDK: 37, Min SDK: 21, Target SDK: 35.
- Baseline build verified successful in local environment.
