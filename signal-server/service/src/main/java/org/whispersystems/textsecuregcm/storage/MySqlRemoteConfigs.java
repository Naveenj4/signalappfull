/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.util.SystemMapper;

/**
 * MySQL/MariaDB implementation of RemoteConfigs.
 * Persists remote configuration flags and rollout percentages in `remote_configs` table.
 */
public class MySqlRemoteConfigs extends RemoteConfigs {

  private static final Logger logger = LoggerFactory.getLogger(MySqlRemoteConfigs.class);
  private static final TypeReference<Set<UUID>> UUID_SET_TYPE = new TypeReference<>() {};

  private final Jdbi jdbi;

  public MySqlRemoteConfigs(final Jdbi jdbi) {
    super();
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
  }

  @Override
  public void set(final RemoteConfig remoteConfig) {
    String uuidsJson = null;
    if (remoteConfig.getUuids() != null && !remoteConfig.getUuids().isEmpty()) {
      try {
        uuidsJson = SystemMapper.jsonMapper().writeValueAsString(remoteConfig.getUuids());
      } catch (final Exception e) {
        logger.warn("Failed to serialize UUID set for remote config {}", remoteConfig.getName(), e);
      }
    }

    final String finalUuidsJson = uuidsJson;
    jdbi.useHandle(handle ->
        handle.createUpdate(
            "INSERT INTO remote_configs (config_name, percentage, uuids, default_value, config_value, hash_key) " +
            "VALUES (:name, :percentage, :uuids, :defaultValue, :value, :hashKey) " +
            "ON DUPLICATE KEY UPDATE " +
            "percentage = VALUES(percentage), " +
            "uuids = VALUES(uuids), " +
            "default_value = VALUES(default_value), " +
            "config_value = VALUES(config_value), " +
            "hash_key = VALUES(hash_key)")
            .bind("name", remoteConfig.getName())
            .bind("percentage", remoteConfig.getPercentage())
            .bind("uuids", finalUuidsJson != null ? finalUuidsJson.getBytes() : null)
            .bind("defaultValue", remoteConfig.getDefaultValue())
            .bind("value", remoteConfig.getValue())
            .bind("hashKey", remoteConfig.getHashKey())
            .execute()
    );
  }

  @Override
  public List<RemoteConfig> getAll() {
    return jdbi.withHandle(handle ->
        handle.createQuery("SELECT config_name, percentage, uuids, default_value, config_value, hash_key FROM remote_configs")
            .map((rs, ctx) -> {
              final String name = rs.getString("config_name");
              final int percentage = rs.getInt("percentage");
              final byte[] uuidsBytes = rs.getBytes("uuids");
              final String defaultValue = rs.getString("default_value");
              final String value = rs.getString("config_value");
              final String hashKey = rs.getString("hash_key");

              Set<UUID> uuids = Collections.emptySet();
              if (uuidsBytes != null && uuidsBytes.length > 0) {
                try {
                  uuids = SystemMapper.jsonMapper().readValue(uuidsBytes, UUID_SET_TYPE);
                } catch (final Exception e) {
                  logger.warn("Failed to deserialize UUID set for remote config {}", name, e);
                }
              }

              return new RemoteConfig(name, percentage, uuids, defaultValue, value, hashKey);
            })
            .list()
    );
  }

  @Override
  public void delete(final String name) {
    jdbi.useHandle(handle ->
        handle.createUpdate("DELETE FROM remote_configs WHERE config_name = :name")
            .bind("name", name)
            .execute()
    );
  }
}
