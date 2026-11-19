/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage.devicecheck;

import com.webauthn4j.appattest.authenticator.DCAppleDevice;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.whispersystems.textsecuregcm.storage.Account;

/**
 * No-op stub implementation of AppleDeviceChecks.
 * Eliminates Apple DeviceCheck and DynamoDB table dependencies for private company deployments.
 */
public class StubAppleDeviceChecks extends AppleDeviceChecks {

  public StubAppleDeviceChecks() {
    super();
  }

  @Override
  public List<byte[]> keyIds(final Account account) {
    return Collections.emptyList();
  }

  @Override
  public boolean storeAttestation(final Account account, final byte[] keyId, final DCAppleDevice appleDevice) {
    return true;
  }

  @Override
  public Optional<DCAppleDevice> lookup(final Account account, final byte[] keyId) {
    return Optional.empty();
  }

  @Override
  public boolean updateCounter(final Account account, final byte[] keyId, final long newCounter) {
    return true;
  }
}
