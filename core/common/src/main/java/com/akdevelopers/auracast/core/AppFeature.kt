package com.akdevelopers.auracast.core

import android.content.Context

/**
 * AppFeature — contract for any pluggable feature module in AuraCast.
 *
 * HOW TO ADD A NEW FEATURE
 * ────────────────────────
 * 1. Create a class in a new sub-package, e.g. `feature/recording/RecordingFeature.kt`.
 * 2. Implement [AppFeature].
 * 3. Register it from the application composition root via
 *    `appGraph.featureRegistry.register(...)`.
 * 4. The feature will be automatically initialised by
 *    [com.akdevelopers.auracast.AuraCastApp].
 *
 * Example:
 * ```kotlin
 * class RecordingFeature : AppFeature {
 *     override val featureId = "recording"
 *     override fun initialize(context: Context) { /* start recording observer */ }
 *     override fun tearDown() { /* release resources */ }
 * }
 * ```
 */
interface AppFeature {

    /**
     * Unique snake_case identifier for this feature.
     * Used for logging and feature-flag lookup.
     */
    val featureId: String

    /**
     * Called once during [android.app.Application.onCreate].
     * Use this to register listeners, start background observers, etc.
     *
     * @param context Application context — never Activity context.
     */
    fun initialize(context: Context)

    /**
     * Called when the feature should clean up all resources.
     * Implementations must be idempotent (safe to call multiple times).
     */
    fun tearDown()
}
