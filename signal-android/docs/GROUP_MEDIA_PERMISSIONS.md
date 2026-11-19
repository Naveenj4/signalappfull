# SECURECHAT – Group Admin-Only Photo Permission Specification

## 1. Objective & Architectural Scope
This document specifies the design and implementation of **Feature C: Group Photo Permission System**.

### Core Rule:
- **Group Administrators**: PERMITTED to send photo attachments in groups.
- **Group Creator with Admin rights**: PERMITTED to send photo attachments in groups.
- **Regular Group Members**: STRICTLY DENIED from sending photo attachments in groups.
- **1:1 Conversations**: UNRESTRICTED (all users can send photos in 1:1 private conversations).
- **Text & Voice Messaging**: UNRESTRICTED (regular group members retain full rights to send text, voice notes, and standard non-photo communications).

---

## 2. Role Identification & Mapping

Signal GroupsV2 natively tracks group membership and roles in `GroupTable.kt`:
```kotlin
enum class MemberLevel(val isInGroup: Boolean) {
  NOT_A_MEMBER(false),
  PENDING_MEMBER(false),
  REQUESTING_MEMBER(false),
  FULL_MEMBER(true),
  ADMINISTRATOR(true)
}
```

### Role Evaluation via `GroupRecord.kt`:
```kotlin
val isAdmin: Boolean = groupRecord.isAdmin(Recipient.self())
val memberLevel: GroupTable.MemberLevel = groupRecord.memberLevel(Recipient.self())
```

### Role Matrix:
| Role | Group Text | Group Photos | 1:1 Photos |
| :--- | :---: | :---: | :---: |
| **Group Creator (Admin)** | ALLOWED | **ALLOWED** | ALLOWED |
| **Promoted Admin** | ALLOWED | **ALLOWED** | ALLOWED |
| **Regular Member (`FULL_MEMBER`)** | ALLOWED | **BLOCKED** | ALLOWED |
| **Demoted Admin (`FULL_MEMBER`)** | ALLOWED | **BLOCKED** | ALLOWED |
| **Removed / Non-Member** | BLOCKED | **BLOCKED** | ALLOWED |

---

## 3. Multi-Stage Authorization Pipeline

Hiding the photo button in the UI is necessary for user experience, but authorization must be enforced across all layers of the sending pipeline to prevent bypass.

```
[User Action]
      │
      ▼
Stage 1: UI Selection & Picker (ConversationFragment)
      │ ─── If Regular Member in Group ──► Reject & show Toast: "Only group administrators can send photos in this group"
      ▼
Stage 2: Staging & Preparation (MediaSendV3Repository / BlobProvider)
      │ ─── Verify sender is Admin for Target Group ──► Throw SecurityException if non-admin
      ▼
Stage 3: Message Composition & Queue (ConversationActivity / OutgoingMessage)
      │ ─── Intercept photo attachments destined for GroupId ──► Block if not Admin
      ▼
Stage 4: Outgoing Job Dispatch (PushGroupSendJob / AttachmentUploadJob)
      │ ─── Re-query GroupTable immediately before network transmission
      ▼
Stage 5: Incoming Group Message Validation (PushDecryptJob / AttachmentDownloadJob)
      │ ─── If incoming message contains photo from non-admin ──► Suppress plaintext rendering
      ▼
[Delivered / Rendered]
```

### Stage 1: UI Selection Interception
- In `ConversationFragment.kt`:
  - When in a group conversation, inspect `recipient.requireGroupId()`.
  - Check whether `Recipient.self()` has `ADMINISTRATOR` status.
  - If regular member:
    - Quick Camera button: Suppress photo attachment action.
    - Media / Attachment Picker: Intercept image selections and display:
      *"Only group administrators can send photos in this group."*
    - Keyboard / Clipboard image pasting: Blocked.

### Stage 2: Media Preparation & Staging
- In `MediaExportPolicy.kt` / `GroupMediaPermissionManager.kt`:
  - Provide `isGroupPhotoSendAllowed(recipient: Recipient)`:
    - If `recipient.isGroup`: Query `SignalDatabase.groups.getGroup(recipient.requireGroupId())`.
    - Returns `true` only if `group.isAdmin(Recipient.self())`.
    - If `!recipient.isGroup`: Returns `true` (preserving 1:1 photo messaging).

### Stage 3: Outgoing Message Dispatch
- In `PushGroupSendJob.java` / `GroupSendJobHelper.java`:
  - Before encrypting and sending the group message payload, verify that if `message.hasAttachments()`, sender possesses admin privileges on the group.
  - If the sender was demoted between composition and transmission, the send job fails immediately with a fatal permission error.

### Stage 4: Incoming Validation & Rendering Suppression
- When an incoming group message is received:
  - Verify sender identity against the local authenticated `GroupRecord`.
  - If sender is not an administrator, mark the media part as unauthorized and suppress image rendering.

---

## 4. Dynamic Group Role Transitions

1. **Promotion (Member -> Admin)**:
   - When a group update message is processed by `GroupsV2StateProcessor`, the local database updates `memberLevel` to `ADMINISTRATOR`.
   - `ConversationFragment` receives updated `ConversationGroupMemberLevel`.
   - Photo attachment controls become immediately active.
2. **Demotion (Admin -> Member)**:
   - `memberLevel` transitions to `FULL_MEMBER`.
   - Active photo selection UI is immediately disabled.
   - Pending outgoing photo jobs in `JobManager` are cancelled.
3. **Removal (Member -> Non-Member)**:
   - `memberLevel` transitions to `NOT_A_MEMBER`.
   - All group messaging actions are revoked.

---

## 5. End-to-End Encryption & Server Trust Boundary

### E2EE Protocol Constraints:
Signal's backend server does not have access to plaintext message contents or attachment metadata. The server cannot inspect whether an encrypted payload is a photo, video, or text message.

### Enforcement Strategy:
1. **Client-Side Cryptographic & Database Binding**: The client enforces strict role validation bound to the cryptographically signed `GroupMutation` log in GroupsV2.
2. **Sender-Side Mandatory Checks**: All compliant clients enforce multi-stage authorization before staging, uploading, and transmitting.
3. **Receiver-Side Authentication**: Receiving clients validate sender administrator credentials against their local authenticated group state before displaying media.
4. **Server-Side Policy Extension (Optional Future Enhancement)**: For hostile or rogue client resistance, the server can be extended with authenticated role tokens attached to attachment upload requests.

