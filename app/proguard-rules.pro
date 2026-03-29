# =============================================================================
#  AuraCast — ProGuard / R8 rules
#  Keep everything that is referenced by name at runtime or via reflection.
# =============================================================================

# ── Debugging / crash reporting ───────────────────────────────────────────────
# Preserve original source file names and line numbers so Crashlytics stack
# traces remain readable even after full minification.
-keepattributes SourceFile, LineNumberTable, *Annotation*, InnerClasses, Signature
-renamesourcefileattribute SourceFile

# ── AuraCast — service & receiver components (Android binds by name) ─────────
-keep class com.akdevelopers.auracast.service.StreamingService      { *; }
-keep class com.akdevelopers.auracast.system.BootReceiver           { *; }
-keep class com.akdevelopers.auracast.system.WatchdogAlarmReceiver  { *; }
-keep class com.akdevelopers.auracast.system.NetworkChangeReceiver  { *; }
-keep class com.akdevelopers.auracast.system.PhoneCallMonitor       { *; }
-keep class com.akdevelopers.auracast.system.WatchdogWorker         { *; }

# ── AuraCast — audio engine (JNI + reflection) ───────────────────────────────
-keep class com.akdevelopers.auracast.audio.AudioCaptureEngine      { *; }
-keep class com.akdevelopers.auracast.audio.AudioWebSocketClient    { *; }
-keep class com.akdevelopers.auracast.audio.AudioControlClient      { *; }
-keep class com.akdevelopers.auracast.audio.OpusEncoderWrapper      { *; }

# ── AuraCast — model / config types (accessed by name in Firebase & prefs) ───
-keep enum  com.akdevelopers.auracast.service.StreamStatus          { *; }
-keep class com.akdevelopers.auracast.service.StreamIdentity        { *; }
-keep class com.akdevelopers.auracast.audio.AudioQualityConfig      { *; }
-keep enum  com.akdevelopers.auracast.audio.AudioQualityPreset      { *; }

# ── AuraCast — DI / core (ServiceLocator accessed reflectively by features) ──
-keep class com.akdevelopers.auracast.di.**    { *; }
-keep interface com.akdevelopers.auracast.core.** { *; }

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$Companion { *; }
-dontwarn kotlin.**

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── OkHttp / Okio ─────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ── Firebase SDK ──────────────────────────────────────────────────────────────
# The Firebase Gradle plugin adds its own rules; these cover edge cases.
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ── Firebase Crashlytics ──────────────────────────────────────────────────────
-keep public class * extends java.lang.Exception
-keep class com.google.firebase.crashlytics.** { *; }
-dontwarn com.google.firebase.crashlytics.**

# ── AndroidX Lifecycle / ViewModel ───────────────────────────────────────────
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    public <init>(android.app.Application);
}

# ── WorkManager ───────────────────────────────────────────────────────────────
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Android Telephony (PhoneCallMonitor uses reflection on legacy API) ────────
-keep class android.telephony.** { *; }

# ── Android Audio Effects (attached by session ID at runtime) ────────────────
-keep class android.media.audiofx.** { *; }

# ── Opus AAR (JNI — native symbols must not be obfuscated) ───────────────────
-keep class com.theeasiestway.opus.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
