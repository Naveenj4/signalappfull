# Signal Android – Privacy Architecture & Implementation Plan

## 1. Architectural Strategy & Design Principles
The core architecture follows the "Zero Regression, Centralized Enforcement" paradigm:
1. **Never Replace Existing Cryptography**: Maintain Signal's existing Double Ratchet, PQXDH, and `AttachmentSecret` AES-CBC/HMAC at-rest storage.
2. **Centralized Policy Enforcement**: Isolate privacy and export decisions into dedicated domain components rather than scattering ad-hoc checks throughout individual fragments or activities.
3. **Defense-in-Depth**: Apply protections at multiple layers (Window level, Video Surface level, ContentProvider boundary, Domain Policy, UI Action level).

---

## 2. Centralized Architecture Components

### Component A: `PrivacyProtectionManager`
- **Location**: `org.thoughtcrime.securesms.privacy.PrivacyProtectionManager` (and core window bindings in `core/ui`)
- **Responsibilities**:
  - Central authority for determining whether window capture protection must be active.
  - Enforces `WindowManager.LayoutParams.FLAG_SECURE` across all application activities, dialog fragments, and bottom sheets.
  - Re-asserts the secure flag during activity lifecycle events (`onCreate`, `onResume`, `onAttachedToWindow`, configuration change/rotation) to guarantee it cannot be cleared accidentally.
  - Interacts with Android 14+ (API 34) screen capture detection callbacks where supported, logging or alerting on capture attempts.

### Component B: `MediaExportPolicy`
- **Location**: `org.thoughtcrime.securesms.privacy.MediaExportPolicy`
- **Responsibilities**:
  - Central rule evaluator for media export requests (save, share, copy, open with external app).
  - Explicitly distinguishes between **outgoing/user-owned media** and **received/in-app protected media**.
  - Enforces the **Group-Only Photo Sharing Policy**:
    - `isPhotoSendAllowed(isGroup: Boolean): Boolean` -> returns `true` for group conversations; returns `false` for 1:1 person-to-person chats.
  - Returns clear policy decisions:
    - `isSaveToStorageAllowed(mediaUri, isOutgoing): Boolean`
    - `isExternalShareAllowed(mediaUri, isOutgoing): Boolean`
    - `isExternalPackageAccessAllowed(callingPackage): Boolean`
  - Defaults to strictly disallowing export of received media to external apps or shared storage.

### Component C: `SecureMediaRepository`
- **Location**: `org.thoughtcrime.securesms.privacy.SecureMediaRepository`
- **Responsibilities**:
  - Wraps access to decrypted media streams, ensuring they are only fed into internal app consumers (Glide, ExoPlayer).
  - Enforces that no plaintext media is written to shared directories (e.g. `Environment.getExternalStoragePublicDirectory`, MediaStore).
  - Isolates transient decryptions to `context.cacheDir/secure_media_transient/` protected with `.nomedia` markers.

### Component D: `MediaRetentionManager`
- **Location**: `org.thoughtcrime.securesms.privacy.MediaRetentionManager`
- **Responsibilities**:
  - Oversees bounded ephemeral memory and cache usage for in-transit media.
  - Coordinates cache clearing upon user logout, account deletion, or explicit media cleanup events.
  - Connected to `ApplicationContext.onTrimMemory(level)` to purge transient caches under memory pressure.

---

## 3. Detailed Modification Plan per Feature

### Feature 1: Screenshot & Video Screen Recording Protection

#### Existing Implementation
- `core/ui/src/main/java/org/signal/core/ui/WindowExtensions.kt`: `Window.initializeScreenshotSecurity()` checks `CoreUiDependencies.isScreenSecurityEnabled`.
- `lib/video/src/main/java/org/signal/video/VideoPlayer.java`: hosts `PlayerView` with a `SurfaceView` overlay that lacked explicit `setSecure(true)`.

#### Implementation Completed
- In `PrivacyProtectionManager`, configured screen security to be enforced by default across all app windows.
- In `BaseActivity.java`, `FLAG_SECURE` is applied directly in both `onCreate()` and `onResume()`.
- In `TemporaryScreenshotSecurity.kt`, ensured releasing temporary holds never clears `FLAG_SECURE` if global screen security is enabled.
- In `VideoPlayer.java`, explicitly applied `((SurfaceView) surfaceView).setSecure(true)` on `exoView.getVideoSurfaceView()` and added `FLAG_SECURE` to the video window. This guarantees that Android `SurfaceFlinger` hardware layer blocks video screen recording, casting, and projection.

#### Files Modified
- `core/ui/src/main/java/org/signal/core/ui/WindowExtensions.kt`
- `app/src/main/java/org/thoughtcrime/securesms/dependencies/CoreUiDependenciesProvider.kt`
- `app/src/main/java/org/thoughtcrime/securesms/components/TemporaryScreenshotSecurity.kt`
- `app/src/main/java/org/thoughtcrime/securesms/BaseActivity.java`
- `app/src/main/java/org/thoughtcrime/securesms/util/WindowUtil.java`
- `app/src/main/java/org/thoughtcrime/securesms/privacy/PrivacyProtectionManager.kt`
- `lib/video/src/main/java/org/signal/video/VideoPlayer.java`

---

### Feature 2: Group-Only Photo Sharing Policy

#### Specification
- Photo sharing is **permitted only in group conversations**.
- Photo sharing in **1:1 person-to-person chats is disallowed**.

#### Implementation Completed
- In `MediaExportPolicy.kt`, introduced `isPhotoSendAllowed(isGroup: Boolean)`.
- In `ConversationFragment.kt`:
  - `onQuickCameraToggleClicked()`: blocks launching the camera if `!recipient.isGroup`.
  - `AttachmentKeyboardButton.GALLERY`: blocks launching the gallery if `!recipient.isGroup`.
  - `onMediaSelected()`: blocks selecting photo/gif attachments if `!recipient.isGroup`.
  - `onMediaSend()`: intercepts media send results and rejects photo payloads if `!recipient.isGroup`.

#### Files Modified
- `app/src/main/java/org/thoughtcrime/securesms/privacy/MediaExportPolicy.kt`
- `app/src/main/java/org/thoughtcrime/securesms/conversation/v2/ConversationFragment.kt`
- `app/src/test/java/org/thoughtcrime/securesms/privacy/MediaExportPolicyTest.kt`

---

### Feature 3: Block External Media Sharing

#### Implementation Completed
- In `SaveAttachmentUtil.kt`: intercepted `saveAttachments()`. Refuses writes to `MediaStore` for received media.
- In `MediaViewerActivity.kt` & `MediaPreviewFragment.kt`: blocked `SaveMedia` and external `share()` Share Sheet actions.
- In `StoryContextMenu.kt`: blocked saving and external sharing for multimedia stories while keeping text stories shareable.
- In `ConversationFragment.kt`: intercepted `handleSaveAttachment()`.
- In `PartProvider.java`: verifies caller UID and `getCallingPackage()`, rejecting foreign package requests with `SecurityException`.

#### Files Modified
- `app/src/main/java/org/thoughtcrime/securesms/providers/PartProvider.java`
- `app/src/main/java/org/thoughtcrime/securesms/util/SaveAttachmentUtil.kt`
- `app/src/main/java/org/thoughtcrime/securesms/mediaviewer/MediaViewerActivity.kt`
- `app/src/main/java/org/thoughtcrime/securesms/mediapreview/MediaPreviewFragment.kt`
- `app/src/main/java/org/thoughtcrime/securesms/stories/dialogs/StoryContextMenu.kt`

---

### Feature 4: No Permanent Local Media Storage

#### Implementation Completed
- Plaintext media is never persisted into public directories (Gallery, Downloads, DCIM).
- Attachments are decrypted on the fly from encrypted at-rest storage (`parts/`) using `ModernDecryptingPartInputStream`.
- `SecureMediaRepository.kt` restricts transient decryptions to app-private cache directories with `.nomedia`.
- `MediaRetentionManager.kt` purges transient caches on logout, account removal, and `ApplicationContext.onTrimMemory()`.

#### Files Modified
- `app/src/main/java/org/thoughtcrime/securesms/privacy/SecureMediaRepository.kt`
- `app/src/main/java/org/thoughtcrime/securesms/privacy/MediaRetentionManager.kt`
- `app/src/main/java/org/thoughtcrime/securesms/ApplicationContext.java`
- `app/src/test/java/org/thoughtcrime/securesms/privacy/MediaRetentionManagerTest.kt`
