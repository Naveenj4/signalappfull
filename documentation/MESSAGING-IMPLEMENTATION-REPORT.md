# PIT Messenger – Messaging Implementation & Architecture Report (Phase 1)

## 1. Overview & Protocol Invariants
PIT Messenger adopts Signal's state-of-the-art cryptographic messaging protocols without compromising privacy or security:
- **X3DH (Extended Triple Diffie-Hellman)**: For asynchronous, mutual authentication and forward-secret initial session key agreement.
- **Double Ratchet Algorithm**: For ongoing post-compromise security with per-message key derivation.
- **Pairwise End-to-End Encryption**: The server never sees plaintext; message contents are opaque ciphertexts encrypted with ephemeral keys.

---

## 2. Real-Time Message Delivery Flow

```
User A (Sender)                  Signal Server (Dropwizard)             User B (Recipient)
      |                                      |                                  |
      | 1. Encrypt message payload           |                                  |
      |    (Double Ratchet Ciphertext)       |                                  |
      |                                      |                                  |
      | 2. PUT /v1/messages/{uuid}           |                                  |
      |------------------------------------->| 3. Check auth & rate limits      |
      |                                      | 4. Is User B WebSocket open?     |
      |                                      |                                  |
      |                                      | [ Branch A: User B is Online ]   |
      |                                      | 5. Push over WebSocket           |
      |                                      |--------------------------------->|
      |                                      | 6. Return WebSocket ACK          |
      |                                      |<---------------------------------|
      |                                      |                                  |
      |                                      | [ Branch B: User B is Offline ]  |
      |                                      | 5. INSERT into MySQL             |
      |                                      |    `persistent_messages` table   |
      |                                      | 6. Dispatch FCM Push Wakeup      |
      |                                      |                                  |
      | 7. Return 200 OK to Sender           |                                  |
      |<-------------------------------------|                                  |
      |                                      |                                  |
      |                                      | 8. User B reconnects WebSocket   |
      |                                      |<=================================|
      |                                      | 9. Drain `persistent_messages`   |
      |                                      |--------------------------------->|
      |                                      | 10. User B sends Delivery ACK    |
      |                                      |<---------------------------------|
      |                                      | 11. DELETE from MySQL queue      |
      |                                      |                                  |
      | 12. Delivery Receipt pushed          |                                  |
      |<-------------------------------------|                                  |
```

---

## 3. Storage Layer Architecture (MySQL)

In place of proprietary cloud message queues, offline messages are stored securely in MySQL table `persistent_messages`:

```sql
CREATE TABLE IF NOT EXISTS persistent_messages (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_uuid VARCHAR(36) NOT NULL,
    device_id INT UNSIGNED NOT NULL,
    guid VARCHAR(64) NOT NULL,
    source_uuid VARCHAR(36) NOT NULL,
    source_device INT UNSIGNED NOT NULL,
    content_type INT UNSIGNED NOT NULL,
    message LONGBLOB NOT NULL,
    server_timestamp BIGINT UNSIGNED NOT NULL,
    INDEX idx_account_device (account_uuid, device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- **Guarantees**: FIFO sequential ordering per device, atomic queue drains, and immediate row deletion once delivery acknowledgement is received.

---

## 4. Key Source Code References

- **Client Sender Engine**: `org.thoughtcrime.securesms.messages.MessageSender` in `Signal-Android`
- **Server Dispatcher**: [`MessageController.java`](file:///D:/signalappppp/signal-server/service/src/main/java/org/whispersystems/textsecuregcm/controllers/MessageController.java)
- **WebSocket Protocol**: [`WebSocketConnection.java`](file:///D:/signalappppp/signal-server/service/src/main/java/org/whispersystems/textsecuregcm/websocket/WebSocketConnection.java)
- **Relational Storage Bridge**: `MySqlMessages.java` inside `signal-server/service/src/main/java/org/whispersystems/textsecuregcm/storage/`
