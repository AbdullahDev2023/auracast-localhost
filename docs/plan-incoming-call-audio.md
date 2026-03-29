# Plan: Capture Incoming Caller Audio During Phone Calls

## Goal

Stream **both sides** of a phone call to the AuraCast relay server:
- ✅ Outgoing mic — already works via `VOICE_COMMUNICATION` audio source
- ❌ Incoming caller voice — currently blocked; this plan adds it

---

## Why It's Hard

Android does not allow regular apps to record the earpiece/speaker output directly.
`CAPTURE_AUDIO_OUTPUT` — the permission that would do this — is a **system-level permission**
only granted to pre-installed apps.

The **only supported path for a user-installable app on Android 10+ (API 29+)** is the
**`AudioPlaybackCapture` API**, which requires an active **MediaProjection** session that
the user explicitly grants (same mechanism used by screen-recorder apps).

---

## Architecture

```
Mic (VOICE_COMMUNICATION)          ─────────────────────────────┐
                                                                  ├─► AudioCallMixer ──► Opus ──► WebSocket ──► Server
Earpiece (AudioPlaybackCapture)    ─────────────────────────────┘
```

---

## Step-by-Step Plan

### Step 1 — Add MediaProjection permission prompt (one-time, before a call)

- Add a new screen or dialog in `feature:setup` (or a runtime prompt from `StreamingService`)
  that launches `MediaProjectionManager.createScreenCaptureIntent()`.
- Store the granted `MediaProjection` token in `StreamRuntimeStore` or a dedicated holder.
- The user only needs to grant this once per service session.
- If permission is denied, fall back gracefully to mic-only CALL mode (current behaviour).

**Files touched:**
- `feature/setup/` — add a "Grant call recording permission" step
- `domain/streaming/` — add `mediaProjection: MediaProjection?` to runtime state
- `app/src/main/AndroidManifest.xml` — add `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission

---

### Step 2 — Create `IncomingCallAudioCapture`

New file: `data/streaming/src/main/java/com/akdevelopers/auracast/audio/IncomingCallAudioCapture.kt`

Responsibilities:
- Accept a `MediaProjection` token and a `sampleRate`.
- Build an `AudioPlaybackCaptureConfiguration` that captures all audio usage types.
- Open an `AudioRecord` using that configuration.
- Run a background thread that reads PCM frames and delivers them via a callback.
- Expose `start()`, `stop()`, and `setBitrate()` consistent with the existing capture contract.

```kotlin
val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
    .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
    .build()

val audioRecord = AudioRecord.Builder()
    .setAudioPlaybackCaptureConfig(config)
    .setAudioFormat(
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
    )
    .setBufferSizeInBytes(bufferSize)
    .build()
```

> **OEM caveat:** Some manufacturers (Samsung One UI, Xiaomi MIUI) strip the capturable
> `USAGE_VOICE_COMMUNICATION` tag from call audio at the kernel level.
> On those devices the earpiece capture will silently return silence.
> Detect this by checking if captured RMS stays at zero for >2 seconds and log a warning.

---

### Step 3 — Create `AudioCallMixer`

New file: `data/streaming/src/main/java/com/akdevelopers/auracast/audio/AudioCallMixer.kt`

Responsibilities:
- Hold two PCM frame queues: one from the mic, one from earpiece capture.
- On each mix cycle, pop one frame from each queue and add the samples together,
  clamping to the 16-bit range (–32768 to 32767).
- Deliver the mixed frame to the existing `onFrameReady` callback.

Simple additive mix (no ducking needed — both sources are speech at similar levels):

```kotlin
for (i in 0 until samples) {
    val mic      = micFrame.readShortLE(i)
    val earpiece = earpieceFrame.readShortLE(i)
    val mixed    = (mic + earpiece).coerceIn(-32768, 32767).toShort()
    outFrame.writeShortLE(i, mixed)
}
```

Queue overflow policy: if one source falls more than ~200 ms behind, drop the oldest
frames from that queue to stay in sync with the other source.

---

### Step 4 — Update `DefaultStreamOrchestrator.onCallStarted()`

Current behaviour:
```kotlin
audioCaptureFactory = captureFactoryBuilder(AudioQualityConfig.CALL)  // mic only
```

New behaviour:
```kotlin
val projection = StreamRuntimeStore.mediaProjection
if (projection != null) {
    // Dual-source: mic + earpiece, mixed
    audioCaptureFactory = MixedCallCaptureFactory(
        projection   = projection,
        qualityConfig = AudioQualityConfig.CALL,
    )
    audioTransport.sendAudioMode("call_mixed")
} else {
    // Fallback: mic only (existing path)
    audioCaptureFactory = captureFactoryBuilder(AudioQualityConfig.CALL)
    audioTransport.sendAudioMode("call")
}
```

Also update `onCallEnded()` to stop the earpiece `AudioRecord` cleanly before reverting
to the normal mic factory.

---

### Step 5 — Create `MixedCallCaptureFactory`

New file: `data/streaming/src/main/java/com/akdevelopers/auracast/data/streaming/MixedCallCaptureFactory.kt`

Implements `AudioCaptureFactory`. When `create()` is called:
1. Start the existing mic `AudioRecord` (VOICE_COMMUNICATION source).
2. Start `IncomingCallAudioCapture` using the held `MediaProjection`.
3. Wire both into `AudioCallMixer`.
4. Return an `AudioCaptureController` that stops both on `stop()`.

---

### Step 6 — Update server & browser for the new mode signal

The transport already calls `sendAudioMode("call")` today.
Add a new mode value `"call_mixed"` so the browser dashboard can display the right label.

In `server/src/modules/relay-module`:
- Forward the `audioMode` message to all browser listeners (already done for `"call"`).
- No structural change needed — just make sure `"call_mixed"` isn't filtered out.

In `server/public/player.html`:
- Update the mode label: `"call"` → 📞 *Call (mic only)*, `"call_mixed"` → 📞 *Call (both sides)*.

---

### Step 7 — Handle the `FOREGROUND_SERVICE_MEDIA_PROJECTION` requirement (Android 14+)

Android 14 requires a foreground service with type `mediaProjection` to hold a
`MediaProjection` token. `StreamingService` already runs as a foreground service.

In `app/src/main/AndroidManifest.xml` add:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />

<service
    android:name=".service.StreamingService"
    android:foregroundServiceType="microphone|mediaProjection"
    ... />
```

---

## New Files Summary

| File | Purpose |
|---|---|
| `data/streaming/.../audio/IncomingCallAudioCapture.kt` | AudioRecord wrapper using AudioPlaybackCapture |
| `data/streaming/.../audio/AudioCallMixer.kt` | Mixes two PCM streams into one |
| `data/streaming/.../streaming/MixedCallCaptureFactory.kt` | AudioCaptureFactory for dual-source call mode |

---

## Changed Files Summary

| File | Change |
|---|---|
| `DefaultStreamOrchestrator.kt` | `onCallStarted()` picks mixed vs mic-only based on MediaProjection availability |
| `StreamRuntimeStore` | Hold optional `MediaProjection` token |
| `feature/setup/` | Add one-time MediaProjection permission prompt |
| `AndroidManifest.xml` | Add `FOREGROUND_SERVICE_MEDIA_PROJECTION`, update service `foregroundServiceType` |
| `server/public/player.html` | Show correct label for `call_mixed` mode |

---

## Limitations & Known Risks

| Risk | Mitigation |
|---|---|
| OEM blocks earpiece capture (Samsung, Xiaomi) | Detect silent capture by RMS check; log warning; fall back to mic-only |
| Android <10 (API <29) — `AudioPlaybackCapture` not available | Gate behind `Build.VERSION.SDK_INT >= 29`; show "not supported" in setup |
| User denies MediaProjection | Graceful fallback to existing mic-only CALL mode |
| MediaProjection token expires if service restarts | Re-request on next service start; store intent in persistent prefs |
| Earpiece and mic frames arriving at different rates | AudioCallMixer queue with 200 ms drift tolerance + drop policy |
