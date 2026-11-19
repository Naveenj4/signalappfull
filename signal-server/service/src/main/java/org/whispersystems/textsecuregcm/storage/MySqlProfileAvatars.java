/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

public class MySqlProfileAvatars extends ProfileAvatars {

  private final Jdbi jdbi;

  public MySqlProfileAvatars(final Jdbi jdbi) {
    super();
    this.jdbi = jdbi;
  }

  @Override
  public Optional<String> setAvatarUrl(final byte[] identity, final String url) {
    return jdbi.inTransaction(handle -> {
      final Optional<String> previousUrl = handle.createQuery("""
          SELECT avatar_data FROM profile_avatars WHERE identity = :identity
          """)
          .bind("identity", identity)
          .mapTo(byte[].class)
          .findFirst()
          .map(b -> new String(b, StandardCharsets.UTF_8));

      handle.createUpdate("""
          INSERT INTO profile_avatars (identity, avatar_data)
          VALUES (:identity, :avatarData)
          ON DUPLICATE KEY UPDATE avatar_data = VALUES(avatar_data)
          """)
          .bind("identity", identity)
          .bind("avatarData", url.getBytes(StandardCharsets.UTF_8))
          .execute();

      return previousUrl;
    });
  }

  @Override
  public Optional<String> updateAvatarTtl(final byte[] identity) {
    return jdbi.withHandle(handle -> handle.createQuery("""
        SELECT avatar_data FROM profile_avatars WHERE identity = :identity
        """)
        .bind("identity", identity)
        .mapTo(byte[].class)
        .findFirst()
        .map(b -> new String(b, StandardCharsets.UTF_8)));
  }

  @Override
  public Optional<String> deleteAvatarUrl(final byte[] identity) {
    return jdbi.inTransaction(handle -> {
      final Optional<String> previousUrl = handle.createQuery("""
          SELECT avatar_data FROM profile_avatars WHERE identity = :identity
          """)
          .bind("identity", identity)
          .mapTo(byte[].class)
          .findFirst()
          .map(b -> new String(b, StandardCharsets.UTF_8));

      handle.createUpdate("DELETE FROM profile_avatars WHERE identity = :identity")
          .bind("identity", identity)
          .execute();

      return previousUrl;
    });
  }
}
