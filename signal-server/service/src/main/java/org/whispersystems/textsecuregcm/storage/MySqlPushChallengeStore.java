/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.util.UUIDUtil;

/**
 * MySQL/MariaDB implementation of PushChallengeDynamoDb.
 * Stores push challenge tokens in the `push_challenges` table.
 */
public class MySqlPushChallengeStore extends PushChallengeDynamoDb {

  private static final Logger logger = LoggerFactory.getLogger(MySqlPushChallengeStore.class);

  private final Jdbi jdbi;
  private final Clock clock;

  public MySqlPushChallengeStore(final Jdbi jdbi, final Clock clock) {
    super();
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public boolean add(final UUID accountUuid, final byte[] challengeToken, final Duration ttl) {
    final byte[] uuidBytes = UUIDUtil.toBytes(accountUuid);
    final long now = clock.instant().getEpochSecond();
    final long expiresAt = now + ttl.toSeconds();

    return jdbi.inTransaction(handle -> {
      // Purge any expired token for this UUID first
      handle.createUpdate("DELETE FROM push_challenges WHERE account_uuid = :uuid AND ttl < :now")
          .bind("uuid", uuidBytes)
          .bind("now", now)
          .execute();

      try {
        final int rows = handle.createUpdate(
            "INSERT INTO push_challenges (account_uuid, challenge_token, ttl) " +
            "VALUES (:uuid, :token, :ttl)")
            .bind("uuid", uuidBytes)
            .bind("token", challengeToken)
            .bind("ttl", expiresAt)
            .execute();
        return rows > 0;
      } catch (final Exception e) {
        // Primary key collision (active token already exists)
        return false;
      }
    });
  }

  @Override
  public boolean remove(final UUID accountUuid, final byte[] challengeToken) {
    final byte[] uuidBytes = UUIDUtil.toBytes(accountUuid);
    final long now = clock.instant().getEpochSecond();

    final int deleted = jdbi.withHandle(handle ->
        handle.createUpdate(
            "DELETE FROM push_challenges " +
            "WHERE account_uuid = :uuid AND challenge_token = :token AND ttl >= :now")
            .bind("uuid", uuidBytes)
            .bind("token", challengeToken)
            .bind("now", now)
            .execute()
    );
    return deleted > 0;
  }
}
