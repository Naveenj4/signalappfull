/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.dependencies

import org.signal.core.ui.CoreUiDependencies
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.privacy.PrivacyProtectionManager

object CoreUiDependenciesProvider : CoreUiDependencies.Provider {
  override fun providePackageId(): String {
    return BuildConfig.APPLICATION_ID
  }

  override fun provideIsIncognitoKeyboardEnabled(): Boolean {
    return SignalStore.settings.isIncognitoKeyboardEnabled
  }

  override fun provideIsScreenSecurityEnabled(): Boolean {
    return PrivacyProtectionManager.isScreenSecurityEnabled()
  }
}
