/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.securevaluerecovery;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * No-op stub implementation of SecureValueRecoveryClient.
 * Eliminates dependencies on external proprietary Signal cloud SVR/SGX enclave services.
 */
public class StubSecureValueRecoveryClient extends SecureValueRecoveryClient {

  public StubSecureValueRecoveryClient() {
    super();
  }

  @Override
  public CompletableFuture<Void> removeData(final UUID accountUuid) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> removeData(final String userIdentifier) {
    return CompletableFuture.completedFuture(null);
  }
}
