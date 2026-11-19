/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms

import android.app.Application
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependencies
import org.thoughtcrime.securesms.privacy.PrivacyProtectionManager

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class BaseActivitySecurityTest {

  @Before
  fun setUp() {
    PrivacyProtectionManager.isScreenSecurityEnforced = true
    CoreUiDependencies.testInject(
      ApplicationProvider.getApplicationContext(),
      object : CoreUiDependencies.Provider {
        override fun providePackageId(): String = "org.thoughtcrime.securesms"
        override fun provideIsIncognitoKeyboardEnabled(): Boolean = false
        override fun provideIsScreenSecurityEnabled(): Boolean = true
      }
    )
  }

  @Test
  fun `base activity applies secure flag when screen security is enabled`() {
    val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()

    assertThat(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0).isTrue()
  }

  @Test
  fun `base activity clears secure flag when screen security is disabled`() {
    PrivacyProtectionManager.isScreenSecurityEnforced = false
    CoreUiDependencies.testInject(
      ApplicationProvider.getApplicationContext(),
      object : CoreUiDependencies.Provider {
        override fun providePackageId(): String = "org.thoughtcrime.securesms"
        override fun provideIsIncognitoKeyboardEnabled(): Boolean = false
        override fun provideIsScreenSecurityEnabled(): Boolean = false
      }
    )

    val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()

    assertThat(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0).isFalse()
  }

  private class TestActivity : BaseActivity()
}
