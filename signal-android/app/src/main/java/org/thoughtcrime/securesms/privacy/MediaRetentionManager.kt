/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.privacy

import android.content.Context
import org.signal.core.util.logging.Log
import java.io.File

/**
 * Manages lifecycle-triggered cleanup of temporary media caches, thumbnails,
 * and transient buffers to prevent permanent residual artifacts.
 */
object MediaRetentionManager {

  private val TAG = Log.tag(MediaRetentionManager::class.java)

  /**
   * Cleans all temporary decrypted media files from the app-private cache directory.
   */
  @JvmStatic
  fun cleanTransientMediaCache(context: Context) {
    try {
      val transientDir = SecureMediaRepository.getTransientMediaDirectory(context)
      if (transientDir.exists()) {
        val files = transientDir.listFiles()
        if (files != null) {
          for (file in files) {
            if (file.name != ".nomedia") {
              file.delete()
            }
          }
        }
      }
      Log.i(TAG, "Transient media cache cleaned successfully.")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to clean transient media cache", e)
    }
  }

  /**
   * Triggered upon user logout, account reset, or full cache expiration.
   */
  @JvmStatic
  fun onUserLogoutOrAccountCleared(context: Context) {
    Log.i(TAG, "Performing complete privacy media cleanup on account termination.")
    cleanTransientMediaCache(context)

    // Clear internal glide / thumbnail disk cache if applicable
    try {
      val imageCacheDir = File(context.cacheDir, "image_manager_disk_cache")
      if (imageCacheDir.exists()) {
        imageCacheDir.deleteRecursively()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to clear image cache directory", e)
    }
  }

  /**
   * Responds to system trim-memory signals to release ephemeral decryptions.
   */
  @JvmStatic
  fun onTrimMemory(context: Context, level: Int) {
    cleanTransientMediaCache(context)
  }
}

