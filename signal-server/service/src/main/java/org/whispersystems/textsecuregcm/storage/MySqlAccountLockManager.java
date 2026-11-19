/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.whispersystems.textsecuregcm.util.ThrowingSupplier;

/**
 * JVM/MySQL pessimistic lock manager for account operations.
 * Locks accounts deterministically in sorted UUID order to prevent deadlocks.
 */
public class MySqlAccountLockManager extends AccountLockManager {

  private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

  public MySqlAccountLockManager() {
    super();
  }

  @Override
  public <V, E extends Exception> V withLock(
      final Set<UUID> phoneNumberIdentifiers,
      final ThrowingSupplier<V, E> task) throws E {

    if (phoneNumberIdentifiers == null || phoneNumberIdentifiers.isEmpty()) {
      throw new IllegalArgumentException("List of PNIs to lock must not be empty");
    }

    final List<UUID> sortedPnis = phoneNumberIdentifiers.stream().sorted().toList();
    final List<ReentrantLock> acquiredLocks = new ArrayList<>(sortedPnis.size());

    try {
      for (final UUID pni : sortedPnis) {
        final ReentrantLock lock = locks.computeIfAbsent(pni, k -> new ReentrantLock());
        lock.lock();
        acquiredLocks.add(lock);
      }
      return task.get();
    } finally {
      for (int i = acquiredLocks.size() - 1; i >= 0; i--) {
        acquiredLocks.get(i).unlock();
      }
    }
  }
}
