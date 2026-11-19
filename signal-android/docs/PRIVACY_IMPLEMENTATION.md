# SECURECHAT – Privacy & Security Implementation Specification

## 1. Overview
This specification details the technical implementation for:
- **Feature A**: Centralized Screenshot & Screen Recording Protection.
- **Feature B**: Preserved One-to-One Private Photo Messaging.
- **Feature D**: Prevention of External Media Sharing and Leakage.
- **Feature E**: Temporary Encrypted Media Storage and Cache Retention.

---

## 2. Feature A: Centralized Screenshot & Recording Protection

### 2.1 Core Architectural Component: `PrivacyProtectionManager.kt`
A centralized singleton managing window security across all activities, fragments, and overlays:
- **Window Flag Enforcement**: Unconditionally applies `WindowManager.LayoutParams.FLAG_SECURE` to every active `Window`.
- **Hardware Compositor Security**: Configures `SurfaceView.setSecure(true)` on `ExoPlayer` video render surfaces (`lib/video/src/main/java/org/signal/video/VideoPlayer.java`), blocking hardware video grabbers and screen recording apps.
- **Android 14+ Detection API**: Registers `ScreenCaptureCallback` (`Activity.registerScreenCaptureCallback`) to capture and audit screenshot attempts without compromising user safety.

### 2.2 Protected Surfaces
- `BaseActivity`: Inherited by all conversation, settings, and media activities.
- `TemporaryScreenshotSecurity.kt`: Ensures `FLAG_SECURE` is never dropped or temporarily stripped during fragment navigation or dialog transitions.
- `MediaViewerActivity` & `MediaPreviewFragment`: Full screen image/video rendering windows.
- Recent Apps Task Thumbnail: By maintaining `FLAG_SECURE` continuously, Android OS renders a blank/black window snapshot in the app switcher.

---

## 3. Feature B: Preserved One-to-One Photo Messaging

### 3.1 Unchanged 1:1 Messaging Functionality
As mandated by the master specification:
- **1:1 Photo Sending**: Both regular users and administrators can freely attach, preview, and send photos in 1:1 private conversations.
- **Multiple Attachments**: Multi-photo selection and gallery batches are supported in 1:1 chats.
- **Attachment Encryption**: End-to-end encryption via AES-256 and Signal Protocol Double Ratchet is fully preserved with zero regression.
- **System Photo Picker**: Uses Android's privacy-preserving Photo Picker (`PickVisualMedia`), eliminating the need for broad gallery read permissions.

---

## 4. Feature D: External Media Sharing Protection

### 4.1 Threat Model & Attack Vectors
Prevent received media from escaping the application sandbox:
1. **Direct Save to Gallery / Storage**:
   - `SaveAttachmentUtil.kt`: Block writing to `MediaStore.Images.Media` and `MediaStore.Downloads`. Display feedback: *"Exporting received media is disabled for security."*
2. **External Application Share Sheet (`ACTION_SEND`)**:
   - `MediaViewerActivity.kt`, `MediaPreviewFragment.kt`, `StoryContextMenu.kt`: Remove or disable the share action button for received protected media.
3. **ContentProvider Interception (`PartProvider.java`)**:
   - Restrict `${applicationId}.part` to internal application UID.
   - Any external package attempting to resolve content URIs via `PartProvider` will receive a `SecurityException: "External access to secure media is prohibited"`.
4. **Clipboard & Drag-and-Drop**:
   - Clipboard export of image data is suppressed for received attachments.

---

## 5. Feature E: Temporary Encrypted Media Storage

### 5.1 Storage Architecture
- Received media attachments remain encrypted at rest in the app's private sandbox directory (`/data/data/org.thoughtcrime.securesms/app_parts`).
- **No Shared Storage**: No decrypted media files are ever written to `/sdcard/`, `Pictures/`, `Movies/`, or `Downloads/`.
- Decryption happens exclusively via streaming buffers (`ModernDecryptingPartInputStream`) directly into memory for display in Glide or ExoPlayer.

### 5.2 Cache Lifecycle Management: `MediaRetentionManager.kt`
- Manages an ephemeral cache directory (`cache/secure_media_ephemeral/`) tagged with `.nomedia`.
- **Automatic Lifecycle Cleanup**:
  - `onTrimMemory()` in `ApplicationContext.java`: Purges temporary decrypted views when app is backgrounded.
  - User Logout / Deregistration: Performs cryptographic key purge and deletes all cached media files.
  - App Process Death: Ephemeral cache is swept and cleaned during `AppStartup`.

