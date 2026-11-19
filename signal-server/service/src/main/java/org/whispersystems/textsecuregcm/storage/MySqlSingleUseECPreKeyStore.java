/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jdbi.v3.core.Jdbi;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.entities.ECPreKey;

public class MySqlSingleUseECPreKeyStore extends SingleUseECPreKeyStore {

  private static final Logger log = LoggerFactory.getLogger(MySqlSingleUseECPreKeyStore.class);

  private final Jdbi jdbi;
  private final Clock clock;

  public MySqlSingleUseECPreKeyStore(final Jdbi jdbi, final Clock clock) {
    super();
    this.jdbi = jdbi;
    this.clock = clock;
  }

  @Override
  public CompletableFuture<Void> store(final UUID identifier, final byte deviceId, final List<ECPreKey> preKeys) {
    return CompletableFuture.runAsync(() -> {
      final String uuidStr = identifier.toString();
      final long now = clock.millis();

      jdbi.useTransaction(handle -> {
        // Clear previously-stored keys for this device
        handle.createUpdate("DELETE FROM ec_one_time_prekeys WHERE account_uuid = :uuid AND device_id = :deviceId")
            .bind("uuid", uuidStr)
            .bind("deviceId", deviceId)
            .execute();

        // Batch insert new prekeys
        final var batch = handle.prepareBatch("""
            INSERT INTO ec_one_time_prekeys (account_uuid, device_id, key_id, public_key, created_at)
            VALUES (:uuid, :deviceId, :keyId, :publicKey, :createdAt)
            """);

        for (final ECPreKey preKey : preKeys) {
          batch.bind("uuid", uuidStr)
              .bind("deviceId", deviceId)
              .bind("keyId", preKey.keyId())
              .bind("publicKey", preKey.serializedPublicKey())
              .bind("createdAt", now)
              .add();
        }
        batch.execute();
      });
    });
  }

  @Override
  public CompletableFuture<Optional<ECPreKey>> take(final UUID identifier, final byte deviceId) {
    return CompletableFuture.supplyAsync(() -> {
      final String uuidStr = identifier.toString();

      return jdbi.inTransaction(handle -> {
        // Step 1: SELECT row with FOR UPDATE SKIP LOCKED
        record KeyRecord(long keyId, byte[] publicKey) {}

        final Optional<KeyRecord> maybeRecord = handle.createQuery("""
            SELECT key_id, public_key
            FROM ec_one_time_prekeys
            WHERE account_uuid = :uuid AND device_id = :deviceId
            ORDER BY key_id ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
            .bind("uuid", uuidStr)
            .bind("deviceId", deviceId)
            .map((rs, ctx) -> new KeyRecord(rs.getLong("key_id"), rs.getBytes("public_key")))
            .findFirst();

        if (maybeRecord.isEmpty()) {
          return Optional.empty();
        }

        final KeyRecord record = maybeRecord.get();

        // Step 2: DELETE the chosen key inside the same transaction
        handle.createUpdate("""
            DELETE FROM ec_one_time_prekeys
            WHERE account_uuid = :uuid AND device_id = :deviceId AND key_id = :keyId
            """)
            .bind("uuid", uuidStr)
            .bind("deviceId", deviceId)
            .bind("keyId", record.keyId())
            .execute();

        try {
          return Optional.of(new ECPreKey(record.keyId(), new ECPublicKey(record.publicKey())));
        } catch (final InvalidKeyException e) {
          log.error("Invalid EC public key for account {} device {}", identifier, deviceId, e);
          return Optional.empty();
        }
      });
    });
  }

  @Override
  public CompletableFuture<Integer> getCount(final UUID identifier, final byte deviceId) {
    return CompletableFuture.supplyAsync(() -> jdbi.withHandle(handle -> handle.createQuery("""
        SELECT COUNT(*)
        FROM ec_one_time_prekeys
        WHERE account_uuid = :uuid AND device_id = :deviceId
        """)
        .bind("uuid", identifier.toString())
        .bind("deviceId", deviceId)
        .mapTo(Integer.class)
        .one()));
  }

  @Override
  public CompletableFuture<Void> delete(final UUID identifier) {
    return CompletableFuture.runAsync(() -> jdbi.useHandle(handle -> handle.createUpdate("""
        DELETE FROM ec_one_time_prekeys WHERE account_uuid = :uuid
        """)
        .bind("uuid", identifier.toString())
        .execute()));
  }

  @Override
  public CompletableFuture<Void> delete(final UUID identifier, final byte deviceId) {
    return CompletableFuture.runAsync(() -> jdbi.useHandle(handle -> handle.createUpdate("""
        DELETE FROM ec_one_time_prekeys WHERE account_uuid = :uuid AND device_id = :deviceId
        """)
        .bind("uuid", identifier.toString())
        .bind("deviceId", deviceId)
        .execute()));
  }
}
