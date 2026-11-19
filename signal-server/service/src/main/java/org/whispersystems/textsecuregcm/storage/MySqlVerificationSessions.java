/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.registration.VerificationSession;
import org.whispersystems.textsecuregcm.util.SystemMapper;

/**
 * MySQL/MariaDB implementation of VerificationSessions.
 * Stores registration verification session states in `verification_sessions` table.
 */
public class MySqlVerificationSessions extends VerificationSessions {

  private static final Logger logger = LoggerFactory.getLogger(MySqlVerificationSessions.class);

  private final Jdbi jdbi;
  private final Clock clock;

  public MySqlVerificationSessions(final Jdbi jdbi, final Clock clock) {
    super();
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public void insert(final String key, final VerificationSession v) {
    final String serialized = serialize(v);
    final long expiration = getExpiration(v);

    jdbi.useHandle(handle ->
        handle.createUpdate(
            "INSERT INTO verification_sessions (session_key, serialized_data, expiration) " +
            "VALUES (:key, :data, :exp)")
            .bind("key", key)
            .bind("data", serialized)
            .bind("exp", expiration)
            .execute()
    );
  }

  @Override
  public void update(final String key, final VerificationSession v) {
    final String serialized = serialize(v);
    final long expiration = getExpiration(v);

    jdbi.useHandle(handle ->
        handle.createUpdate(
            "INSERT INTO verification_sessions (session_key, serialized_data, expiration) " +
            "VALUES (:key, :data, :exp) " +
            "ON DUPLICATE KEY UPDATE serialized_data = VALUES(serialized_data), expiration = VALUES(expiration)")
            .bind("key", key)
            .bind("data", serialized)
            .bind("exp", expiration)
            .execute()
    );
  }

  @Override
  public Optional<VerificationSession> findForKey(final String key) {
    final long now = clock.instant().getEpochSecond();
    return jdbi.withHandle(handle ->
        handle.createQuery(
            "SELECT serialized_data FROM verification_sessions " +
            "WHERE session_key = :key AND expiration >= :now")
            .bind("key", key)
            .bind("now", now)
            .map((rs, ctx) -> {
              try {
                return SystemMapper.jsonMapper().readValue(rs.getString("serialized_data"), VerificationSession.class);
              } catch (final Exception e) {
                logger.error("Failed to parse verification session JSON for key {}", key, e);
                return null;
              }
            })
            .findOne()
    );
  }

  @Override
  public void remove(final String key) {
    jdbi.useHandle(handle ->
        handle.createUpdate("DELETE FROM verification_sessions WHERE session_key = :key")
            .bind("key", key)
            .execute()
    );
  }

  private String serialize(final VerificationSession session) {
    try {
      return SystemMapper.jsonMapper().writeValueAsString(session);
    } catch (final Exception e) {
      throw new AssertionError("Failed to serialize VerificationSession", e);
    }
  }

  private long getExpiration(final VerificationSession session) {
    if (session instanceof Expireable expireable) {
      return expireable.getExpirationEpochSeconds();
    }
    return clock.instant().plusSeconds(86400).getEpochSecond();
  }
}
