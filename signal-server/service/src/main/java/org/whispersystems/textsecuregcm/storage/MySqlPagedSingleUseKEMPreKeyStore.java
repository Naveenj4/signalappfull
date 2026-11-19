/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jdbi.v3.core.Jdbi;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.entities.KEMSignedPreKey;
import reactor.core.publisher.Flux;

public class MySqlPagedSingleUseKEMPreKeyStore extends PagedSingleUseKEMPreKeyStore {

  private static final Logger log = LoggerFactory.getLogger(MySqlPagedSingleUseKEMPreKeyStore.class);

  private final Jdbi jdbi;
  private final Clock clock;

  public MySqlPagedSingleUseKEMPreKeyStore(final Jdbi jdbi, final Clock clock) {
    super();
    this.jdbi = jdbi;
    this.clock = clock;
  }

  @Override
  public CompletableFuture<Void> store(final UUID identifier, final byte deviceId, final List<KEMSignedPreKey> preKeys) {
    return CompletableFuture.runAsync(() -> {
      if (preKeys.isEmpty()) {
        return;
      }

      final String uuidStr = identifier.toString();
      final List<KEMSignedPreKey> sorted = preKeys.stream()
          .sorted(Comparator.comparing(KEMSignedPreKey::keyId))
          .toList();

      final ByteBuffer serialized = KEMPreKeyPage.serialize(KEMPreKeyPage.FORMAT, sorted);
      final byte[] pageBytes = serialized.array();
      final long now = clock.millis();

      jdbi.useTransaction(handle -> {
        // Clear old pages for this device
        handle.createUpdate("DELETE FROM paged_pq_prekeys WHERE account_uuid = :uuid AND device_id = :deviceId")
            .bind("uuid", uuidStr)
            .bind("deviceId", deviceId)
            .execute();

        // Insert new page bundle
        handle.createUpdate("""
            INSERT INTO paged_pq_prekeys (account_uuid, device_id, batch_id, keys_data, created_at)
            VALUES (:uuid, :deviceId, :batchId, :keysData, :createdAt)
            """)
            .bind("uuid", uuidStr)
            .bind("deviceId", deviceId)
            .bind("batchId", 0)
            .bind("keysData", pageBytes)
            .bind("createdAt", now)
            .execute();
      });
    });
  }

  @Override
  public CompletableFuture<Optional<KEMSignedPreKey>> take(final UUID identifier, final byte deviceId) {
    return CompletableFuture.supplyAsync(() -> {
      final String uuidStr = identifier.toString();

      return jdbi.inTransaction(handle -> {
        record PageRecord(int batchId, byte[] keysData) {}

        final Optional<PageRecord> maybeRecord = handle.createQuery("""
            SELECT batch_id, keys_data
            FROM paged_pq_prekeys
            WHERE account_uuid = :uuid AND device_id = :deviceId
            ORDER BY batch_id ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
            .bind("uuid", uuidStr)
            .bind("deviceId", deviceId)
            .map((rs, ctx) -> new PageRecord(rs.getInt("batch_id"), rs.getBytes("keys_data")))
            .findFirst();

        if (maybeRecord.isEmpty()) {
          return Optional.empty();
        }

        final PageRecord record = maybeRecord.get();
        final List<KEMSignedPreKey> keys = parsePage(record.keysData());

        if (keys.isEmpty()) {
          handle.createUpdate("DELETE FROM paged_pq_prekeys WHERE account_uuid = :uuid AND device_id = :deviceId AND batch_id = :batchId")
              .bind("uuid", uuidStr)
              .bind("deviceId", deviceId)
              .bind("batchId", record.batchId())
              .execute();
          return Optional.empty();
        }

        final KEMSignedPreKey taken = keys.remove(0);

        if (keys.isEmpty()) {
          handle.createUpdate("DELETE FROM paged_pq_prekeys WHERE account_uuid = :uuid AND device_id = :deviceId AND batch_id = :batchId")
              .bind("uuid", uuidStr)
              .bind("deviceId", deviceId)
              .bind("batchId", record.batchId())
              .execute();
        } else {
          final ByteBuffer newBuffer = KEMPreKeyPage.serialize(KEMPreKeyPage.FORMAT, keys);
          handle.createUpdate("""
              UPDATE paged_pq_prekeys
              SET keys_data = :newData
              WHERE account_uuid = :uuid AND device_id = :deviceId AND batch_id = :batchId
              """)
              .bind("newData", newBuffer.array())
              .bind("uuid", uuidStr)
              .bind("deviceId", deviceId)
              .bind("batchId", record.batchId())
              .execute();
        }

        return Optional.of(taken);
      });
    });
  }

  @Override
  public CompletableFuture<Integer> getCount(final UUID identifier, final byte deviceId) {
    return CompletableFuture.supplyAsync(() -> jdbi.withHandle(handle -> {
      final List<byte[]> allPages = handle.createQuery("""
          SELECT keys_data FROM paged_pq_prekeys WHERE account_uuid = :uuid AND device_id = :deviceId
          """)
          .bind("uuid", identifier.toString())
          .bind("deviceId", deviceId)
          .mapTo(byte[].class)
          .list();

      int count = 0;
      for (final byte[] page : allPages) {
        count += parsePage(page).size();
      }
      return count;
    }));
  }

  @Override
  public CompletableFuture<Void> delete(final UUID identifier) {
    return CompletableFuture.runAsync(() -> jdbi.useHandle(handle -> handle.createUpdate("""
        DELETE FROM paged_pq_prekeys WHERE account_uuid = :uuid
        """)
        .bind("uuid", identifier.toString())
        .execute()));
  }

  @Override
  public CompletableFuture<Void> delete(final UUID identifier, final byte deviceId) {
    return CompletableFuture.runAsync(() -> jdbi.useHandle(handle -> handle.createUpdate("""
        DELETE FROM paged_pq_prekeys WHERE account_uuid = :uuid AND device_id = :deviceId
        """)
        .bind("uuid", identifier.toString())
        .bind("deviceId", deviceId)
        .execute()));
  }

  @Override
  public Flux<DeviceKEMPreKeyPages> listStoredPages(int lookupConcurrency) {
    return Flux.empty();
  }

  private List<KEMSignedPreKey> parsePage(final byte[] pageBytes) {
    final List<KEMSignedPreKey> keys = new ArrayList<>();
    if (pageBytes == null || pageBytes.length <= KEMPreKeyPage.HEADER_SIZE) {
      return keys;
    }

    final ByteBuffer buffer = ByteBuffer.wrap(pageBytes);
    buffer.position(KEMPreKeyPage.HEADER_SIZE);

    while (buffer.remaining() >= KEMPreKeyPage.SERIALIZED_PREKEY_LENGTH) {
      final ByteBuffer slice = buffer.slice(buffer.position(), KEMPreKeyPage.SERIALIZED_PREKEY_LENGTH);
      try {
        keys.add(KEMPreKeyPage.deserializeKey(KEMPreKeyPage.FORMAT, slice));
      } catch (final InvalidKeyException e) {
        log.error("Failed to parse serialized KEM prekey from page", e);
      }
      buffer.position(buffer.position() + KEMPreKeyPage.SERIALIZED_PREKEY_LENGTH);
    }
    return keys;
  }
}
