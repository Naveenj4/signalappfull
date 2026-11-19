/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import org.jdbi.v3.core.Jdbi;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

public class MySqlProfilesV2 extends ProfilesV2 {

  private final Jdbi jdbi;

  public MySqlProfilesV2(final Jdbi jdbi) {
    super();
    this.jdbi = jdbi;
  }

  @Override
  public void set(final UUID uuid,
      final byte[] version,
      final byte[] data,
      final byte[] dataHash,
      @Nullable final byte[] commitment,
      @Nullable final byte[] paymentAddress,
      @Nullable final byte[] paymentAddressHash,
      @Nullable final byte[] expectedCurrentDataHash,
      final TransactWriteItem profileV1TransactWriteItem) throws WriteConflictException {

    if (expectedCurrentDataHash == null && commitment == null) {
      throw new IllegalArgumentException("Commitment is required for initial write");
    }

    jdbi.useTransaction(handle -> {
      handle.createUpdate("""
          INSERT INTO profiles_v2 (account_uuid, version, profile_data)
          VALUES (:uuid, :version, :data)
          ON DUPLICATE KEY UPDATE profile_data = VALUES(profile_data)
          """)
          .bind("uuid", uuid.toString())
          .bind("version", version)
          .bind("data", data)
          .execute();
    });
  }

  @Override
  public Optional<VersionedProfile> get(final UUID uuid, final byte[] version) {
    return jdbi.withHandle(handle -> handle.createQuery("""
        SELECT version, profile_data
        FROM profiles_v2
        WHERE account_uuid = :uuid AND version = :version
        """)
        .bind("uuid", uuid.toString())
        .bind("version", version)
        .map((rs, ctx) -> new VersionedProfile(
            rs.getBytes("version"),
            rs.getBytes("profile_data"),
            new byte[0], // dataHash
            null, // paymentAddress
            null, // paymentAddressHash
            null // commitment
        ))
        .findFirst());
  }

  @Override
  public CompletableFuture<Void> deleteAll(final UUID uuid) {
    return CompletableFuture.runAsync(() -> jdbi.useHandle(handle -> handle.createUpdate("""
        DELETE FROM profiles_v2 WHERE account_uuid = :uuid
        """)
        .bind("uuid", uuid.toString())
        .execute()));
  }
}
