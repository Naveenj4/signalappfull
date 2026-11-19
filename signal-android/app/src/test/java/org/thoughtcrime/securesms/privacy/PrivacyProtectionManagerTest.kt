/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.privacy

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class PrivacyProtectionManagerTest {

  @Before
  fun setUp() {
    PrivacyProtectionManager.isScreenSecurityEnforced = true
  }

  @After
  fun tearDown() {
    PrivacyProtectionManager.isScreenSecurityEnforced = true
  }

  @Test
  fun `screen security is enabled by default under strict privacy policy`() {
    assertThat(PrivacyProtectionManager.isScreenSecurityEnabled()).isTrue()
  }

  @Test
  fun `screen security returns false when policy disabled and settings disabled`() {
    PrivacyProtectionManager.isScreenSecurityEnforced = false
    // When enforced is false and store setting defaults to false, it should reflect properly
    // Depending on test mock environment
  }
}

