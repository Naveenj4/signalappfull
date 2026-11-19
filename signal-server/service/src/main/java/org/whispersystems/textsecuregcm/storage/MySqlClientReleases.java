/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import com.vdurmont.semver4j.Semver;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.util.ua.ClientPlatform;

/**
 * MySQL/MariaDB implementation of ClientReleases.
 * Reads release information from the `client_releases` table.
 */
public class MySqlClientReleases extends ClientReleases {

  private static final Logger logger = LoggerFactory.getLogger(MySqlClientReleases.class);

  private final Jdbi jdbi;

  public MySqlClientReleases(final Jdbi jdbi) {
    super();
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
  }

  @Override
  public Map<ClientPlatform, Map<Semver, ClientRelease>> getClientReleases() {
    try {
      return jdbi.withHandle(handle -> {
        final Map<ClientPlatform, Map<Semver, ClientRelease>> result = new HashMap<>();

        handle.createQuery("SELECT platform, version, release_timestamp, expiration FROM client_releases")
            .map((rs, ctx) -> {
              try {
                final ClientPlatform platform = ClientPlatform.valueOf(rs.getString("platform"));
                final Semver version = new Semver(rs.getString("version"));
                final Instant release = Instant.ofEpochSecond(rs.getLong("release_timestamp"));
                final Instant expiration = Instant.ofEpochSecond(rs.getLong("expiration"));
                return new ClientRelease(platform, version, release, expiration);
              } catch (final Exception e) {
                logger.warn("Failed to parse client release row from database", e);
                return null;
              }
            })
            .list()
            .stream()
            .filter(Objects::nonNull)
            .forEach(release ->
                result.computeIfAbsent(release.platform(), k -> new HashMap<>())
                    .put(release.version(), release));

        return Collections.unmodifiableMap(result);
      });
    } catch (final Exception e) {
      logger.error("Failed to fetch client releases from MySQL", e);
      return Collections.emptyMap();
    }
  }
}
