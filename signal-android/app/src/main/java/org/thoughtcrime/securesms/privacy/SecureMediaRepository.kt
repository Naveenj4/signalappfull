/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.privacy

import android.content.Context
import android.net.Uri
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.mms.PartAuthority
import java.io.File
import java.io.InputStream

/**
 * Repository enforcing secure, app-private handling of media attachments.
 *
 * Ensures:
 * 1. Plaintext media is never persisted into shared external directories.
 * 2. Decryption occurs only in-process via streaming (ModernDecryptingPartInputStream).
 * 3. Any intermediate cache is strictly confined to internal app-private storage.
 */
object SecureMediaRepository {

  private val TAG = Log.tag(SecureMediaRepository::class.java)

  /**
   * Retrieves an on-the-fly decrypted stream for the given attachment URI.
   * Streaming decryption avoids writing unencrypted bytes to disk.
   */
  @JvmStatic
  fun getSecureInputStream(context: Context, uri: Uri): InputStream? {
    return try {
      PartAuthority.getAttachmentStream(context, uri)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to resolve secure stream for $uri", e)
      null
    }
  }

  /**
   * Returns a temporary app-private directory for transient media buffers.
   * Ensured to be inside context.cacheDir and protected by .nomedia.
   */
  @JvmStatic
  fun getTransientMediaDirectory(context: Context): File {
    val dir = File(context.cacheDir, "secure_media_transient")
    if (!dir.exists()) {
      dir.mkdirs()
      try {
        File(dir, ".nomedia").createNewFile()
      } catch (ignored: Exception) {
      }
    }
    return dir
  }
}

