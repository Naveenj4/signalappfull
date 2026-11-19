# SECURECHAT – Database Architecture & Migration Analysis

## 1. Compatibility Policy
The master specification dictates:
- **Zero Destruction**: Preserve existing accounts, messages, groups, encryption identities, and attachment records.
- **Backward Compatibility**: Seamless operation across application updates without requiring full data wipes or breaking existing SQLCipher encryption.

---

## 2. Existing Schema Audit (`SignalDatabase`)

Signal uses an encrypted SQLCipher SQLite database with comprehensive migration history (currently at version ~330+).

### Key Tables Involved:
1. **`groups` (`GroupTable.kt`)**:
   - `group_id`: Primary group identifier (BLOB or string).
   - `recipient_id`: Associated `Recipient` identifier.
   - `members`: Serialized list of member `RecipientId`s.
   - `avatar_id`, `title`, `description`, etc.
   - `member_level`: Computed based on decrypted group state in GroupsV2.
2. **`group_membership` (`GroupMembershipTable.kt`)**:
   - Maps `group_id` to `recipient_id` and tracks individual member roles.
3. **`part` (`AttachmentTable.kt`)**:
   - Stores encrypted attachment part references, file paths, content types (`image/jpeg`, `image/png`, `video/mp4`), transfer state, and digests.
4. **`mms` / `sms` (`MessageTable.kt`)**:
   - Message records linking threads, senders, timestamps, and receipt statuses.

---

## 3. Schema Strategy for Admin-Only Group Photo Policy

### Reusing Existing Authenticated Group Properties
GroupsV2 in Signal already stores cryptographically authenticated role state:
- Each member in `decryptedGroup.members` has a `role` field:
  - `Member.Role.ADMINISTRATOR`
  - `Member.Role.DEFAULT`
- `GroupRecord.isAdmin(recipient)` directly inspects this authenticated structure.

### Conclusion on Database Migration:
Because Signal's GroupsV2 architecture already maintains cryptographically signed and local administrator status per group member, **no destructive database migration or table schema alteration is required**. 

This guarantees:
1. 100% preservation of all existing conversations, messages, and attachments.
2. Existing group records remain intact and instantly functional.
3. Zero risk of SQLCipher migration failures on existing databases.

