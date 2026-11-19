/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.whispersystems.textsecuregcm.auth.SaltedTokenHash;

/**
 * MySQL/MariaDB implementation of PhoneNumberRecoveryPasswords.
 * Stores salted recovery passwords mapped by PNI in `phone_number_recovery_passwords`.
 */
public class MySqlPhoneNumberRecoveryPasswords extends PhoneNumberRecoveryPasswords {

  private final Jdbi jdbi;
  private final Duration expiration;
  private final Clock clock;

  public MySqlPhoneNumberRecoveryPasswords(
      final Jdbi jdbi,
      final Duration expiration,
      final Clock clock) {
    super();
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
    this.expiration = Objects.requireNonNull(expiration, "expiration must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Optional<SaltedTokenHash> lookup(final UUID phoneNumberIdentifier) {
    return jdbi.withHandle(handle ->
        handle.createQuery(
            "SELECT salt, hash FROM phone_number_recovery_passwords " +
            "WHERE pni = :pni AND expiration >= :now")
            .bind("pni", phoneNumberIdentifier.toString())
            .bind("now", clock.instant().getEpochSecond())
            .map((rs, ctx) -> new SaltedTokenHash(rs.getString("hash"), rs.getString("salt")))
            .findOne()
    );
  }

  @Override
  public boolean addOrReplace(final UUID phoneNumberIdentifier, final SaltedTokenHash data) {
    final long expirationSeconds = clock.instant().plus(expiration).getEpochSecond();
    final String pniString = phoneNumberIdentifier.toString();

    return jdbi.inTransaction(handle -> {
      final boolean existed = handle.createQuery("SELECT 1 FROM phone_number_recovery_passwords WHERE pni = :pni")
          .bind("pni", pniString)
          .mapTo(Integer.class)
          .findOne()
          .isPresent();

      handle.createUpdate(
          "INSERT INTO phone_number_recovery_passwords (pni, salt, hash, expiration) " +
          "VALUES (:pni, :salt, :hash, :expiration) " +
          "ON DUPLICATE KEY UPDATE salt = VALUES(salt), hash = VALUES(hash), expiration = VALUES(expiration)")
          .bind("pni", pniString)
          .bind("salt", data.salt())
          .bind("hash", data.hash())
          .bind("expiration", expirationSeconds)
          .execute();

      return !existed;
    });
  }

  @Override
  public boolean removeEntry(final UUID phoneNumberIdentifier) {
    final int rows = jdbi.withHandle(handle ->
        handle.createUpdate("DELETE FROM phone_number_recovery_passwords WHERE pni = :pni")
            .bind("pni", phoneNumberIdentifier.toString())
            .execute()
    );
    return rows > 0;
  }
}
