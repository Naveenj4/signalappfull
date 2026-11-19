/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.privacy

import android.app.Activity
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.annotation.MainThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.util.Collections
import java.util.WeakHashMap

/**
 * Central authority for window-level screenshot and screen recording protection.
 *
 * Coordinates WindowManager.LayoutParams.FLAG_SECURE application across all application
 * windows, activities, dialogs, and media surfaces to prevent capture and task previews.
 */
object PrivacyProtectionManager {

  private val TAG = Log.tag(PrivacyProtectionManager::class.java)

  /**
   * Screen security is enforced by default to satisfy strict privacy and secure media requirements.
   */
  @Volatile
  var isScreenSecurityEnforced: Boolean = true

  private val registeredCallbacks = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

  /**
   * Determines whether screenshot and screen recording protection is currently enabled.
   */
  @JvmStatic
  fun isScreenSecurityEnabled(): Boolean {
    return true
  }

  /**
   * Applies screenshot and screen recording protection to the target Window.
   *
   * Must be applied before sensitive content is attached to ensure no transient
   * un-secured frames are captured by the system compositor.
   */
  @JvmStatic
  fun applyProtection(window: Window?) {
    if (window == null) return
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  }

  /**
   * Applies screenshot and screen recording protection to the target Activity.
   *
   * Re-asserts FLAG_SECURE on the window and sets up capture detection on supported OS versions.
   */
  @JvmStatic
  @MainThread
  fun applyProtection(activity: Activity?) {
    if (activity == null) return

    applyProtection(activity.window)
    registerScreenCaptureCallbackIfSupported(activity)
  }

  /**
   * Handles Android 14+ (API 34) screen capture detection callback gracefully.
   * Does not replace FLAG_SECURE; provides auditing of capture attempts.
   */
  @MainThread
  private fun registerScreenCaptureCallbackIfSupported(activity: Activity) {
    if (Build.VERSION.SDK_INT >= 34) {
      if (registeredCallbacks.contains(activity)) {
        return
      }

      try {
        val callback = Activity.ScreenCaptureCallback {
          Log.w(TAG, "Screen capture attempt detected for activity: ${activity.javaClass.simpleName}")
        }
        activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
        registeredCallbacks.add(activity)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to register screen capture callback on API ${Build.VERSION.SDK_INT}", t)
      }
    }
  }
}

