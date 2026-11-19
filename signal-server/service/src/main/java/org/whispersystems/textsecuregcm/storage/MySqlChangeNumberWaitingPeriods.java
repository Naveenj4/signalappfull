/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.whispersystems.textsecuregcm.util.UUIDUtil;

/**
 * MySQL/MariaDB implementation of ChangeNumberWaitingPeriods.
 * Tracks cooling-off periods for phone number change operations in `change_number_waiting_periods`.
 */
public class MySqlChangeNumberWaitingPeriods extends ChangeNumberWaitingPeriods {

  private final Jdbi jdbi;

  public MySqlChangeNumberWaitingPeriods(final Jdbi jdbi) {
    super();
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
  }

  @Override
  public void setExpiration(final UUID aci, final Instant expiration) {
    jdbi.useHandle(handle ->
        handle.createUpdate(
            "INSERT INTO change_number_waiting_periods (account_uuid, expiration) " +
            "VALUES (:uuid, :expiration) " +
            "ON DUPLICATE KEY UPDATE expiration = VALUES(expiration)")
            .bind("uuid", UUIDUtil.toBytes(aci))
            .bind("expiration", expiration.getEpochSecond())
            .execute()
    );
  }

  @Override
  public Optional<Instant> getExpiration(final UUID aci) {
    final long now = Instant.now().getEpochSecond();
    return jdbi.withHandle(handle ->
        handle.createQuery(
            "SELECT expiration FROM change_number_waiting_periods " +
            "WHERE account_uuid = :uuid AND expiration > :now")
            .bind("uuid", UUIDUtil.toBytes(aci))
            .bind("now", now)
            .mapTo(Long.class)
            .findOne()
            .map(Instant::ofEpochSecond)
    );
  }

  @Override
  public void delete(final UUID aci) {
    jdbi.useHandle(handle ->
        handle.createUpdate("DELETE FROM change_number_waiting_periods WHERE account_uuid = :uuid")
            .bind("uuid", UUIDUtil.toBytes(aci))
            .execute()
    );
  }
}
