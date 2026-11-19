/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jdbi.v3.core.Jdbi;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

public class MySqlProfiles extends Profiles {

  private final Jdbi jdbi;

  public MySqlProfiles(final Jdbi jdbi) {
    super();
    this.jdbi = jdbi;
  }

  @Override
  public void set(final UUID uuid, final VersionedProfileV1 profile) {
    jdbi.useHandle(handle -> handle.createUpdate("""
        INSERT INTO profiles_v1 (account_uuid, version, name, about, avatar_path, commitment, profile_data)
        VALUES (:uuid, :version, :name, :about, :avatarPath, :commitment, :profileData)
        ON DUPLICATE KEY UPDATE
          name = VALUES(name),
          about = VALUES(about),
          avatar_path = VALUES(avatar_path),
          commitment = VALUES(commitment),
          profile_data = VALUES(profile_data)
        """)
        .bind("uuid", uuid.toString())
        .bind("version", profile.version())
        .bind("name", profile.name())
        .bind("about", profile.about())
        .bind("avatarPath", profile.avatar())
        .bind("commitment", profile.commitment())
        .bind("profileData", (byte[]) null)
        .execute());
  }

  @Override
  public TransactWriteItem getTransactWriteItem(final UUID uuid, final VersionedProfileV1 profile) {
    set(uuid, profile);
    return TransactWriteItem.builder().build();
  }

  @Override
  public Optional<VersionedProfileV1> get(final UUID uuid, final String version) {
    return jdbi.withHandle(handle -> handle.createQuery("""
        SELECT version, name, about, avatar_path, commitment
        FROM profiles_v1
        WHERE account_uuid = :uuid AND version = :version
        """)
        .bind("uuid", uuid.toString())
        .bind("version", version)
        .map((rs, ctx) -> new VersionedProfileV1(
            rs.getString("version"),
            rs.getBytes("name"),
            rs.getString("avatar_path"),
            null, // aboutEmoji
            rs.getBytes("about"),
            null, // paymentAddress
            null, // phoneNumberSharing
            rs.getBytes("commitment")
        ))
        .findFirst());
  }

  @Override
  public CompletableFuture<List<String>> deleteAll(final UUID uuid) {
    return CompletableFuture.supplyAsync(() -> jdbi.inTransaction(handle -> {
      final List<String> avatarPaths = handle.createQuery("""
          SELECT avatar_path FROM profiles_v1 WHERE account_uuid = :uuid AND avatar_path IS NOT NULL
          """)
          .bind("uuid", uuid.toString())
          .mapTo(String.class)
          .list();

      handle.createUpdate("DELETE FROM profiles_v1 WHERE account_uuid = :uuid")
          .bind("uuid", uuid.toString())
          .execute();

      return avatarPaths;
    }));
  }

  @Override
  public VersionedProfileV1 setAvatar(final UUID accountIdentifier, final String version,
      final String avatar, final byte[] commitment) {
    jdbi.useHandle(handle -> handle.createUpdate("""
        UPDATE profiles_v1
        SET avatar_path = :avatar, commitment = :commitment
        WHERE account_uuid = :uuid AND version = :version
        """)
        .bind("uuid", accountIdentifier.toString())
        .bind("version", version)
        .bind("avatar", avatar)
        .bind("commitment", commitment)
        .execute());

    return get(accountIdentifier, version).orElse(null);
  }
}
