/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.privacy

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MediaRetentionManagerTest {

  private lateinit var context: Application

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
  }

  @Test
  fun `getTransientMediaDirectory creates directory with nomedia marker`() {
    val dir = SecureMediaRepository.getTransientMediaDirectory(context)

    assertThat(dir.exists()).isTrue()
    assertThat(File(dir, ".nomedia").exists()).isTrue()
  }

  @Test
  fun `cleanTransientMediaCache removes transient media files but preserves nomedia`() {
    val dir = SecureMediaRepository.getTransientMediaDirectory(context)
    val testFile = File(dir, "temp_stream.bin").apply { writeText("secret_media_bytes") }

    assertThat(testFile.exists()).isTrue()

    MediaRetentionManager.cleanTransientMediaCache(context)

    assertThat(testFile.exists()).isFalse()
    assertThat(File(dir, ".nomedia").exists()).isTrue()
  }

  @Test
  fun `onUserLogoutOrAccountCleared performs full cleanup`() {
    val dir = SecureMediaRepository.getTransientMediaDirectory(context)
    val testFile = File(dir, "logout_test.bin").apply { writeText("logout_test_data") }

    val imageCacheDir = File(context.cacheDir, "image_manager_disk_cache").apply {
      mkdirs()
      File(this, "cached_thumb.png").writeText("thumb_bytes")
    }

    assertThat(testFile.exists()).isTrue()
    assertThat(imageCacheDir.exists()).isTrue()

    MediaRetentionManager.onUserLogoutOrAccountCleared(context)

    assertThat(testFile.exists()).isFalse()
    assertThat(imageCacheDir.exists()).isFalse()
  }
}

