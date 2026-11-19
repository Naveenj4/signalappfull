/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jdbi.v3.core.Jdbi;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.entities.MessageProtos;
import org.whispersystems.textsecuregcm.util.UUIDUtil;
import reactor.core.publisher.Flux;

public class MySqlMessagesStore extends MessagesDynamoDb {

  private static final Logger log = LoggerFactory.getLogger(MySqlMessagesStore.class);
  private static final Duration DEFAULT_TTL = Duration.ofDays(14);

  private final Jdbi jdbi;
  private final Clock clock;
  private final Duration timeToLive;

  public MySqlMessagesStore(final Jdbi jdbi, final Clock clock, final Duration timeToLive) {
    super();
    this.jdbi = jdbi;
    this.clock = clock;
    this.timeToLive = timeToLive != null ? timeToLive : DEFAULT_TTL;
  }

  @Override
  public void store(final List<MessageProtos.Envelope> messages, final UUID destinationAccountUuid,
      final Device destinationDevice) {

    if (messages == null || messages.isEmpty()) {
      return;
    }

    final String destUuidStr = destinationAccountUuid.toString();
    final short deviceId = destinationDevice.getId();
    final long now = clock.millis();
    final long expiresAt = now + timeToLive.toMillis();

    jdbi.useTransaction(handle -> {
      final var batch = handle.prepareBatch("""
          INSERT INTO persistent_messages (
            destination_uuid, destination_device_id, guid, server_timestamp, client_timestamp,
            is_urgent, envelope_data, expires_at
          ) VALUES (
            :destinationUuid, :destinationDeviceId, :guid, :serverTimestamp, :clientTimestamp,
            :isUrgent, :envelopeData, :expiresAt
          )
          ON DUPLICATE KEY UPDATE
            envelope_data = VALUES(envelope_data),
            expires_at = VALUES(expires_at)
          """);

      for (final MessageProtos.Envelope message : messages) {
        UUID messageUuid;
        if (message.getServerGuid() != null && !message.getServerGuid().isEmpty()) {
          try {
            messageUuid = UUIDUtil.fromByteString(message.getServerGuid());
          } catch (final Exception _) {
            messageUuid = UUID.randomUUID();
          }
        } else {
          messageUuid = UUID.randomUUID();
        }
        batch.bind("destinationUuid", destUuidStr)
            .bind("destinationDeviceId", deviceId)
            .bind("guid", messageUuid.toString())
            .bind("serverTimestamp", message.getServerTimestamp())
            .bind("clientTimestamp", message.getClientTimestamp())
            .bind("isUrgent", message.getUrgent())
            .bind("envelopeData", message.toByteArray())
            .bind("expiresAt", expiresAt)
            .add();
      }
      batch.execute();
    });
  }

  @Override
  public CompletableFuture<Boolean> mayHaveMessages(final UUID accountIdentifier, final Device device) {
    return CompletableFuture.supplyAsync(() -> jdbi.withHandle(handle -> handle.createQuery("""
        SELECT EXISTS (
          SELECT 1 FROM persistent_messages
          WHERE destination_uuid = :uuid AND destination_device_id = :deviceId
        )
        """)
        .bind("uuid", accountIdentifier.toString())
        .bind("deviceId", device.getId())
        .mapTo(Boolean.class)
        .one()));
  }

  @Override
  public CompletableFuture<Boolean> mayHaveUrgentMessages(final UUID accountIdentifier, final Device device) {
    return CompletableFuture.supplyAsync(() -> jdbi.withHandle(handle -> handle.createQuery("""
        SELECT EXISTS (
          SELECT 1 FROM persistent_messages
          WHERE destination_uuid = :uuid AND destination_device_id = :deviceId AND is_urgent = TRUE
        )
        """)
        .bind("uuid", accountIdentifier.toString())
        .bind("deviceId", device.getId())
        .mapTo(Boolean.class)
        .one()));
  }

  @Override
  public Publisher<MessageProtos.Envelope> load(final UUID destinationAccountUuid, final Device device,
      final Integer limit) {

    final int queryLimit = (limit != null && limit > 0) ? limit : 100;

    final List<byte[]> envelopeBytesList = jdbi.withHandle(handle -> handle.createQuery("""
        SELECT envelope_data
        FROM persistent_messages
        WHERE destination_uuid = :uuid AND destination_device_id = :deviceId
        ORDER BY server_timestamp ASC
        LIMIT :limit
        """)
        .bind("uuid", destinationAccountUuid.toString())
        .bind("deviceId", device.getId())
        .bind("limit", queryLimit)
        .mapTo(byte[].class)
        .list());

    final List<MessageProtos.Envelope> envelopes = new ArrayList<>(envelopeBytesList.size());
    for (final byte[] bytes : envelopeBytesList) {
      try {
        envelopes.add(MessageProtos.Envelope.parseFrom(bytes));
      } catch (final InvalidProtocolBufferException e) {
        log.error("Failed to parse stored envelope for destination {}", destinationAccountUuid, e);
      }
    }

    return Flux.fromIterable(envelopes);
  }

  @Override
  public CompletableFuture<Optional<MessageProtos.Envelope>> deleteMessage(final UUID destinationAccountUuid,
      final Device destinationDevice, final UUID messageUuid, final long serverTimestamp) {

    return CompletableFuture.supplyAsync(() -> jdbi.inTransaction(handle -> {
      final String uuidStr = destinationAccountUuid.toString();
      final short deviceId = destinationDevice.getId();
      final String guidStr = messageUuid.toString();

      final Optional<byte[]> maybeData = handle.createQuery("""
          SELECT envelope_data
          FROM persistent_messages
          WHERE destination_uuid = :uuid AND destination_device_id = :deviceId AND guid = :guid
          """)
          .bind("uuid", uuidStr)
          .bind("deviceId", deviceId)
          .bind("guid", guidStr)
          .mapTo(byte[].class)
          .findFirst();

      if (maybeData.isEmpty()) {
        return Optional.empty();
      }

      handle.createUpdate("""
          DELETE FROM persistent_messages
          WHERE destination_uuid = :uuid AND destination_device_id = :deviceId AND guid = :guid
          """)
          .bind("uuid", uuidStr)
          .bind("deviceId", deviceId)
          .bind("guid", guidStr)
          .execute();

      try {
        return Optional.of(MessageProtos.Envelope.parseFrom(maybeData.get()));
      } catch (final InvalidProtocolBufferException e) {
        log.error("Failed to parse envelope being deleted {}", messageUuid, e);
        return Optional.empty();
      }
    }));
  }

  @Override
  public void clearAll(final UUID destinationAccountUuid) {
    if (destinationAccountUuid == null) {
      return;
    }
    jdbi.useHandle(handle -> handle.createUpdate("""
        DELETE FROM persistent_messages
        WHERE destination_uuid = :uuid
        """)
        .bind("uuid", destinationAccountUuid.toString())
        .execute());
  }

  @Override
  public void clearAll(final UUID destinationAccountUuid, final byte deviceId) {
    if (destinationAccountUuid == null) {
      return;
    }
    jdbi.useHandle(handle -> handle.createUpdate("""
        DELETE FROM persistent_messages
        WHERE destination_uuid = :uuid AND destination_device_id = :deviceId
        """)
        .bind("uuid", destinationAccountUuid.toString())
        .bind("deviceId", (short) deviceId)
        .execute());
  }
}

