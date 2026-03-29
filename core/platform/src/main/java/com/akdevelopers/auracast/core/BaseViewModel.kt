package com.akdevelopers.auracast.core

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import com.akdevelopers.auracast.core.AppConstants

/**
 * BaseViewModel — shared foundation for all ViewModels in AuraCast.
 *
 * Provides direct access to the application-scoped SharedPreferences so
 * subclasses never need to re-declare the prefs boilerplate.
 *
 * HOW TO ADD A NEW VIEWMODEL
 * ──────────────────────────
 * Extend this class instead of [AndroidViewModel] directly:
 * ```kotlin
 * class RecordingViewModel(app: Application) : BaseViewModel(app) {
 *     fun save() = prefs.edit().putString(AppConstants.PREF_YOUR_KEY, "value").apply()
 * }
 * ```
 */
abstract class BaseViewModel(app: Application) : AndroidViewModel(app) {

    /** App-scoped SharedPreferences — single file shared across the whole app. */
    protected val prefs: SharedPreferences =
        app.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
}
