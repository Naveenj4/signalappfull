# Signal Android – Existing Feature Inventory & Preservation Matrix

## 1. Inventory of Core Features
All listed existing features must remain operational and strictly unmodified in their functional behavior:

| Domain | Feature | Key Classes / Modules | Invariant / Preservation Rule |
| :--- | :--- | :--- | :--- |
| **Identity & Auth** | Registration & Onboarding | `feature/registration`, `RegistrationActivity` | Unaltered SMS/calling/captcha verification protocols |
| | PIN & Account Recovery | `KeyBackupService`, `PinCreationScreen` | Keep secure enclave / KBS interaction intact |
| | Multi-Device Sync | `DeviceProvisioningActivity`, `DeviceSyncJob` | Retain prekey exchange and device linking |
| **Messaging** | 1:1 E2EE Messaging | `ConversationFragment`, `MessageSender` | Retain Signal Protocol (Double Ratchet, PQXDH) |
| | Group Messaging (GV2) | `GroupManager`, `GroupDatabase` | Retain group sender keys and decentralized group credentials |
| | Message Delivery & Receipts | `ReceiptJob`, `DeliveryReceiptJob` | Retain delivery, read, and viewed receipts |
| | Disappearing Messages | `ExpiringMessageManager`, `MessageTable` | Retain existing message expiry timers and cleanup |
| | Reactions & Mentions | `ReactionsBottomSheetDialogFragment`, `Mentions` | Retain inline reactions and push alerts |
| **Media Pipeline** | Outgoing Photo/Video Selection | `MediaSendV3Repository`, `MediaPickerFolderFragment` | Retain system visual picker & gallery selection for outgoing media |
| | In-App Camera & Editing | `feature/media-send`, `ImageEditorFragment` | Retain filters, doodles, cropping, and text overlay |
| | Attachment Encryption | `AttachmentUploadJob`, `AttachmentSecret` | Retain streaming encryption before upload |
| | In-App Media Viewer | `MediaViewerActivity`, `MediaPreviewFragment` | Retain image zoom, video playback, and carousel navigation |
| | Audio & Voice Notes | `AudioRecorder`, `VoiceNoteMediaController` | Retain voice note recording, waveform rendering, and playback |
| **Calling** | End-to-End Encrypted Calling | `WebRtcCallActivity`, `WebRtcService` | Retain audio/video calls, group calling, and ringtones |
| **Storage & Backup** | Encrypted Message Database | `SignalDatabase`, SQLCipher | Do not alter schema, table structures, or existing migrations |
| | Encrypted Part Storage | `AttachmentTable`, `ModernDecryptingPartInputStream` | Retain encrypted at-rest storage in app-private `parts/` |
| | Encrypted Backups | `SignalBackupAgent`, `BackupPassphrase` | Retain encrypted backup archive export and restore |

## 2. Privacy & Export Attack Surface Inventory
The audit identified specific surfaces where content or media could be captured or exported:

### Surface A: Window Capture & Screen Recording
- **Component**: `BaseActivity.java`, `WindowExtensions.kt`, `TemporaryScreenshotSecurity.kt`.
- **Current Behavior**: `FLAG_SECURE` is applied conditionally based on `SignalStore.settings.isScreenSecurityEnabled` (default `false`). When `false`, Android allows taking screenshots, screen recording, and shows clear preview snapshots in the Android recent apps switcher.
- **Requirement**: Mandatory screenshot and recording protection across all sensitive app windows.

### Surface B: Direct Media Export to Public Shared Storage
- **Components**:
  - `SaveAttachmentUtil.kt`: Writes attachments directly to Android `MediaStore` (Public `Pictures`, `Movies`, `Download` directories).
  - `AttachmentSaver.kt`: UI coordinator that requests `WRITE_EXTERNAL_STORAGE` or MediaStore access and notifies the user of saved files.
- **Requirement**: Block saving of received media to external/shared storage.

### Surface C: System Share Sheet & External Content Streaming
- **Components**:
  - `MediaViewerActivity.share()`: Launches Android `ShareCompat.IntentBuilder` with `publicUri` pointing to `PartProvider`.
  - `MediaPreviewFragment.share()`: Invokes chooser intent with `PartAuthority.getAttachmentPublicUri(uri)`.
  - `StoryContextMenu.kt`: Dispatches share intent with raw attachment URI.
  - `PartProvider.java`: ContentProvider with `grantUriPermissions="true"` that streams decrypted media bytes to any target package granted temporary URI permission.
- **Requirement**: Block external share sheets and external content streaming for received media while preserving legitimate text sharing (e.g., invite links, phone numbers).

## 3. High-Risk Architectural Boundaries (Zero-Regression Guarantees)
1. **Outgoing Attachment Flow**: Senders MUST remain able to select images and videos from their device's storage using Android's system photo picker or media selector and send them to contacts or groups. The privacy restrictions apply strictly to *received* and in-app protected media.
2. **Signal Protocol Cryptography**: Under no circumstances should `SignalServiceMessageSender`, ratchet keys, session ciphers, or attachment AES-GCM upload keys be modified.
3. **Database Schema Stability**: No dropping, renaming, or incompatible restructuring of columns in `AttachmentTable` or `MessageTable`.
4. **App Navigation Integrity**: Protection must be applied at the Window and repository layers without breaking fragment navigation, back-stack preservation, or activity transitions.
