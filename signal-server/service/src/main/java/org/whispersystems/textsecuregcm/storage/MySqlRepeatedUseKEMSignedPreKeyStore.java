/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jdbi.v3.core.Jdbi;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.entities.KEMSignedPreKey;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

public class MySqlRepeatedUseKEMSignedPreKeyStore extends RepeatedUseKEMSignedPreKeyStore {

  private static final Logger log = LoggerFactory.getLogger(MySqlRepeatedUseKEMSignedPreKeyStore.class);

  private final Jdbi jdbi;
  private final Clock clock;

  public MySqlRepeatedUseKEMSignedPreKeyStore(final Jdbi jdbi, final Clock clock) {
    super();
    this.jdbi = jdbi;
    this.clock = clock;
  }

  @Override
  public CompletableFuture<Void> store(final UUID identifier, final byte deviceId, final KEMSignedPreKey signedPreKey) {
    return CompletableFuture.runAsync(() -> jdbi.useHandle(handle -> handle.createUpdate("""
        INSERT INTO signed_prekeys (account_uuid, device_id, key_type, key_id, public_key, signature, created_at)
        VALUES (:uuid, :deviceId, 'PQ_LAST_RESORT', :keyId, :publicKey, :signature, :createdAt)
        ON DUPLICATE KEY UPDATE
          key_id = VALUES(key_id),
          public_key = VALUES(public_key),
          signature = VALUES(signature),
          created_at = VALUES(created_at)
        """)
        .bind("uuid", identifier.toString())
        .bind("deviceId", deviceId)
        .bind("keyId", signedPreKey.keyId())
        .bind("publicKey", signedPreKey.serializedPublicKey())
        .bind("signature", signedPreKey.signature())
        .bind("createdAt", clock.millis())
        .execute()));
  }

  @Override
  public CompletableFuture<Optional<KEMSignedPreKey>> find(final UUID identifier, final byte deviceId) {
    return CompletableFuture.supplyAsync(() -> jdbi.withHandle(handle -> handle.createQuery("""
        SELECT key_id, public_key, signature
        FROM signed_prekeys
        WHERE account_uuid = :uuid AND device_id = :deviceId AND key_type = 'PQ_LAST_RESORT'
        """)
        .bind("uuid", identifier.toString())
        .bind("deviceId", deviceId)
        .map((rs, ctx) -> {
          try {
            return new KEMSignedPreKey(
                rs.getLong("key_id"),
                new KEMPublicKey(rs.getBytes("public_key")),
                rs.getBytes("signature"));
          } catch (final InvalidKeyException e) {
            log.error("Invalid KEM last resort prekey for account {} device {}", identifier, deviceId, e);
            throw new RuntimeException(e);
          }
        })
        .findFirst()));
  }

  @Override
  TransactWriteItem buildTransactWriteItemForInsertion(final UUID identifier, final byte deviceId,
      final KEMSignedPreKey preKey) {
    // Eagerly persist in MySQL if invoked inside transactional device creation
    store(identifier, deviceId, preKey).join();
    return TransactWriteItem.builder().build();
  }

  @Override
  public TransactWriteItem buildTransactWriteItemForDeletion(final UUID identifier, final byte deviceId) {
    jdbi.useHandle(handle -> handle.createUpdate("""
        DELETE FROM signed_prekeys
        WHERE account_uuid = :uuid AND device_id = :deviceId AND key_type = 'PQ_LAST_RESORT'
        """)
        .bind("uuid", identifier.toString())
        .bind("deviceId", deviceId)
        .execute());
    return TransactWriteItem.builder().build();
  }
}
