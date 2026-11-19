/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL/MariaDB implementation of ReportMessageDynamoDb.
 * Stores reported spam message hashes in `report_messages` table.
 */
public class MySqlReportMessageStore extends ReportMessageDynamoDb {

  private static final Logger logger = LoggerFactory.getLogger(MySqlReportMessageStore.class);

  private final Jdbi jdbi;
  private final Duration ttl;

  public MySqlReportMessageStore(final Jdbi jdbi, final Duration ttl) {
    super();
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
    this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
  }

  @Override
  public CompletableFuture<Void> store(final byte[] hash) {
    final long expiration = Instant.now().plus(ttl).getEpochSecond();
    jdbi.useHandle(handle ->
        handle.createUpdate(
            "INSERT INTO report_messages (message_hash, ttl) VALUES (:hash, :ttl) " +
            "ON DUPLICATE KEY UPDATE ttl = VALUES(ttl)")
            .bind("hash", hash)
            .bind("ttl", expiration)
            .execute()
    );
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public boolean remove(final byte[] hash) {
    final int deleted = jdbi.withHandle(handle ->
        handle.createUpdate("DELETE FROM report_messages WHERE message_hash = :hash")
            .bind("hash", hash)
            .execute()
    );
    return deleted > 0;
  }
}
