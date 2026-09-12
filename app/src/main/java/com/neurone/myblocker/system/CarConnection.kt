package com.neurone.myblocker.system

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Detects whether the phone is projecting to Android Auto, by reading the same content provider
 * the AndroidX Car app library uses. Android Auto refuses to start while a VPN is active
 * (communication error 21), so the service pauses protection while projection is connected.
 *
 * No dependency on androidx.car.app: we query the provider directly and watch it for changes.
 */
class CarConnection(context: Context, private val callback: (Boolean) -> Unit) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            callback(isProjecting(appContext))
        }
    }
    private var registered = false

    fun start() {
        if (registered) return
        registered = try {
            appContext.contentResolver.registerContentObserver(URI, false, observer)
            true
        } catch (e: Exception) {
            Log.d(TAG, "cannot observe car connection: ${e.message}")
            false
        }
        // Deliver the current state immediately.
        callback(isProjecting(appContext))
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        registered = false
    }

    companion object {
        private const val TAG = "CarConnection"
        private val URI: Uri = Uri.parse("content://androidx.car.app.connection")
        private const val COLUMN = "CarConnectionState"
        private const val STATE_PROJECTION = 2 // 0 = not connected, 1 = native (Automotive OS), 2 = projection (Android Auto)

        /** True when Android Auto is actively projecting from this phone. */
        fun isProjecting(context: Context): Boolean {
            return try {
                context.contentResolver.query(URI, arrayOf(COLUMN), null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(COLUMN)
                    if (idx >= 0 && c.moveToNext()) c.getInt(idx) == STATE_PROJECTION else false
                } ?: false
            } catch (e: Exception) {
                false
            }
        }
    }
}
