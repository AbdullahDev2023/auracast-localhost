package com.akdevelopers.auracast.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.akdevelopers.auracast.core.AppConstants
import kotlinx.coroutines.flow.StateFlow

// ── SharedPreferences ─────────────────────────────────────────────────────────

/** Returns the app-scoped SharedPreferences ([AppConstants.PREFS_FILE]). */
fun Context.auracastPrefs(): SharedPreferences =
    getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)

/** Edits SharedPreferences in a DSL block; applies the change asynchronously. */
inline fun SharedPreferences.update(crossinline block: SharedPreferences.Editor.() -> Unit) =
    edit().apply { block() }.apply()

// ── URL helpers ───────────────────────────────────────────────────────────────

/** Returns true if this string is a valid WebSocket URL (ws:// or wss://). */
fun String.isValidWsUrl(): Boolean =
    startsWith("ws://") || startsWith("wss://")

/** Extracts just the host portion of a ws/wss URL, e.g. "abc.ngrok-free.app". */
fun String.wsHost(): String = runCatching {
    Uri.parse(this).host ?: this
}.getOrDefault(this)

// ── Logging helpers ───────────────────────────────────────────────────────────

/** Log a debug message using the calling class's simple name as tag. */
inline fun <reified T : Any> T.logD(message: String) =
    Log.d(T::class.java.simpleName, message)

/** Log an error message using the calling class's simple name as tag. */
inline fun <reified T : Any> T.logE(message: String, throwable: Throwable? = null) =
    if (throwable != null) Log.e(T::class.java.simpleName, message, throwable)
    else Log.e(T::class.java.simpleName, message)

// ── Flow / LiveData helpers ───────────────────────────────────────────────────

/**
 * Observes a [StateFlow] as [LiveData] within the given [LifecycleOwner]'s scope.
 * Convenience wrapper so fragments/activities don't need lifecycle-scope boilerplate.
 */
fun <T> StateFlow<T>.observeIn(owner: LifecycleOwner, action: (T) -> Unit) {
    asLiveData().observe(owner) { action(it) }
}

// ── Misc ──────────────────────────────────────────────────────────────────────

/** Truncates a string to [maxLength] chars, appending "…" if trimmed. */
fun String.truncate(maxLength: Int): String =
    if (length <= maxLength) this else take(maxLength - 1) + "…"
