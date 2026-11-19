/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.whispersystems.textsecuregcm.util.UUIDUtil;

/**
 * MySQL/MariaDB implementation of SandboxAccounts.
 * Manages the sandbox account identifier pool in `sandbox_accounts`.
 */
public class MySqlSandboxAccounts extends SandboxAccounts {

  private final Jdbi jdbi;

  public MySqlSandboxAccounts(final Jdbi jdbi) {
    super();
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
  }

  @Override
  public List<UUID> getAvailable() {
    return jdbi.withHandle(handle ->
        handle.createQuery("SELECT account_uuid FROM sandbox_accounts WHERE registered_at IS NULL")
            .mapTo(byte[].class)
            .map(UUIDUtil::fromBytes)
            .list()
    );
  }

  @Override
  public List<SandboxAccountReservation> getAll() {
    return jdbi.withHandle(handle ->
        handle.createQuery("SELECT account_uuid, registered_at FROM sandbox_accounts")
            .map((rs, ctx) -> {
              final UUID uuid = UUIDUtil.fromBytes(rs.getBytes("account_uuid"));
              final long registeredAtVal = rs.getLong("registered_at");
              final Optional<Instant> registeredAt = rs.wasNull()
                  ? Optional.empty()
                  : Optional.of(Instant.ofEpochSecond(registeredAtVal));
              return new SandboxAccountReservation(uuid, registeredAt);
            })
            .list()
    );
  }

  @Override
  public boolean releaseAci(final UUID uuid, final Instant expectedRegisteredAt) {
    final int rows = jdbi.withHandle(handle ->
        handle.createUpdate(
            "UPDATE sandbox_accounts SET registered_at = NULL " +
            "WHERE account_uuid = :uuid AND registered_at = :expected")
            .bind("uuid", UUIDUtil.toBytes(uuid))
            .bind("expected", expectedRegisteredAt.getEpochSecond())
            .execute()
    );
    return rows > 0;
  }

  @Override
  public void add(final UUID uuid) {
    jdbi.useHandle(handle ->
        handle.createUpdate(
            "INSERT IGNORE INTO sandbox_accounts (account_uuid, registered_at) VALUES (:uuid, NULL)")
            .bind("uuid", UUIDUtil.toBytes(uuid))
            .execute()
    );
  }

  @Override
  public boolean removeIfAvailable(final UUID uuid) {
    final int rows = jdbi.withHandle(handle ->
        handle.createUpdate(
            "DELETE FROM sandbox_accounts WHERE account_uuid = :uuid AND registered_at IS NULL")
            .bind("uuid", UUIDUtil.toBytes(uuid))
            .execute()
    );
    return rows > 0;
  }
}
