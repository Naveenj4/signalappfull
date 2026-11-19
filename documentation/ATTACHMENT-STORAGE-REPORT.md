# PIT Messenger – Attachment & Media Storage Report (Phase 1)

## 1. Attachment Encryption & Security Model
PIT Messenger preserves Signal's zero-knowledge media storage architecture:
1. **Client-Side Key Generation**: For every attachment, the sender generates a cryptographically random 256-bit AES symmetric key and IV.
2. **Client-Side Encryption**: The raw file is encrypted locally using AES-256-GCM.
3. **Encrypted Upload**: The ciphertext stream is uploaded to the storage service via a presigned PUT URL.
4. **Key Delivery**: The AES key, IV, SHA-256 digest, and download pointer are packaged inside the end-to-end encrypted message body and delivered to the recipient.
5. **Zero Plaintext at Rest**: S3 / MinIO never receives unencrypted file content, metadata, or file names.

---

## 2. API Endpoints & Upload Pipeline

```
Sender Device                          Signal Server                        Object Storage (MinIO / S3)
     |                                      |                                          |
     | 1. GET /v4/attachments/form/upload   |                                          |
     |------------------------------------->| 2. Generate unique attachment ID         |
     |                                      |    Create presigned upload URL           |
     | 3. Returns {uploadUrl, attachmentId} |                                          |
     |<-------------------------------------|                                          |
     |                                                                                 |
     | 4. Encrypt file with AES-256-GCM locally                                        |
     | 5. PUT encrypted ciphertext directly to presigned URL                           |
     |-------------------------------------------------------------------------------->|
     | 6. Returns 200 OK                                                               |
     |<--------------------------------------------------------------------------------|
     |                                                                                 |
     | 7. Send message with {attachmentId, key, iv} inside Double Ratchet ciphertext   |
     |-------------------------------------> (To Recipient Device via Server)          |
```

---

## 3. Storage Infrastructure Comparison

| Dimension | MinIO (Self-Hosted on Linux/Docker) | AWS S3 / Cloudflare R2 |
|---|---|---|
| **Data Sovereignty** | 100% self-hosted on local or private VPS disk | Hosted on cloud multi-tenant infrastructure |
| **Bandwidth Costs** | Zero egress fees | Egress bandwidth costs apply |
| **API Compatibility** | 100% S3-compatible REST API | Standard S3 API |
| **Recommended Target** | **Recommended for PIT Messenger Private Deployment** | Recommended for large multi-region scaling |

---

## 4. Key Source Code References

- **Server Upload Controller**: [`AttachmentControllerV4.java`](file:///D:/signalappppp/signal-server/service/src/main/java/org/whispersystems/textsecuregcm/controllers/AttachmentControllerV4.java)
- **Client Upload Job**: `org.thoughtcrime.securesms.jobs.AttachmentUploadJob`
- **Client Download Job**: `org.thoughtcrime.securesms.jobs.AttachmentDownloadJob`
- **S3 Gateway Interface**: `org.whispersystems.textsecuregcm.s3.S3Client`
