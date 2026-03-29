package com.akdevelopers.auracast.di

import android.app.Application
import android.content.Context
import com.akdevelopers.auracast.core.AppFeature
import com.akdevelopers.auracast.domain.streaming.StreamOrchestrator
import kotlin.reflect.KClass

interface AppGraph {
    val application: Application
    val featureRegistry: FeatureRegistry
    val streamServiceLauncher: StreamServiceLauncher

    fun createStreamOrchestrator(context: Context): StreamOrchestrator
}

interface AppGraphProvider {
    val appGraph: AppGraph
}

class FeatureRegistry(private val appContext: Context) {
    private val features = linkedMapOf<KClass<out AppFeature>, AppFeature>()

    fun <T : AppFeature> register(feature: T): T {
        features[feature::class] = feature
        feature.initialize(appContext)
        return feature
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : AppFeature> get(type: KClass<T>): T? =
        features[type] as? T

    inline fun <reified T : AppFeature> get(): T? = get(T::class)

    fun tearDownAll() {
        features.values.forEach { runCatching { it.tearDown() } }
        features.clear()
    }
}

interface StreamServiceLauncher {
    fun ensureServiceRunning(context: Context, url: String)
    fun startMic(context: Context)
    fun stopMic(context: Context)
    fun stopFull(context: Context)
}
