package com.remotemonitor.watch.ui

import android.content.Context
import android.content.pm.PackageManager

/**
 * True when the app is running on a Wear OS host. The same APK runs on
 * watches, phones and tablets; only the watch host gets the Wear
 * navigation + OngoingActivity surface.
 */
internal fun isWatchDevice(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
