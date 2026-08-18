package com.example.autotexttapper

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Simple shared status holder for the automation.
 *
 * The latest status is persisted in SharedPreferences so it survives process
 * restarts, and in-process listeners (MainActivity) are notified immediately
 * on the main thread while they are registered.
 */
object StatusHolder {

    interface Listener {
        fun onStatusChanged(status: String)
    }

    private const val PREFS_NAME = "auto_text_tapper_status"
    private const val KEY_STATUS = "last_status"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var cached: String = ""

    /** Stores [status] and notifies all registered listeners on the main thread. */
    fun update(context: Context, status: String) {
        cached = status
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, status)
            .apply()
        for (listener in listeners) {
            mainHandler.post { listener.onStatusChanged(status) }
        }
    }

    /** Returns the latest stored status, or an empty string if none was saved yet. */
    fun read(context: Context): String {
        if (cached.isNotEmpty()) return cached
        val stored = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "") ?: ""
        cached = stored
        return stored
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
}
