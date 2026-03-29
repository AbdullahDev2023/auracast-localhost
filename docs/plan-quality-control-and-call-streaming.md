# Plan: Server-Side Quality Control + Phone Call Streaming

## Overview

Two independent features that fit cleanly into the existing architecture:

1. **Quality Control** — server pushes codec/bitrate settings to Android at runtime.
2. **Phone Call Streaming** — AuraCast auto-detects active calls, switches to `VOICE_COMMUNICATION` source.

---

## Feature 1: Server-Side Audio Quality Control

### Problem
`AudioQualityConfig.kt` hard-codes a single `HIGH_QUALITY` preset (48 kHz, 192 kbps, Opus complexity 10).
No way to tune quality at runtime — e.g. drop to 32 kbps on a weak connection.

### End-to-End Flow

```
Dashboard/API → POST /stream-quality?id=<streamId>  { bitrate, sampleRate, frameMs, complexity }
             → server sends {"type":"cmd","action":"set_quality",...} on /control WS
             → Android CommandProcessor dispatches to AudioQualityManager
             → AudioCaptureEngine restarts with new config
             → Android re-announces {"type":"codec",...} on /stream WS
             → Server caches new codecConfig, forwards to browsers
```

---

### Server Changes

#### 1. `server/src/services/channel-registry.ts`
Add `QualityConfig` type and fields to `Channel`:

```typescript
export interface QualityConfig {
    bitrate:    number;  // bps  e.g. 32_000 | 64_000 | 128_000 | 192_000
    sampleRate: number;  // Hz   fixed at 48_000 — not configurable
    frameMs:    number;  // ms   20 | 40 | 60
    complexity: number;  // 0–10
}

// Inside Channel interface — add:
qualityConfig: QualityConfig | null;
audioMode: 'mic' | 'call' | string;
```

Initialize `qualityConfig: null, audioMode: 'mic'` in `getOrCreateChannel`.

#### 2. `server/src/modules/relay-module.ts` — new HTTP route

```typescript
// Add to httpRoutes[]
{
    method: 'POST',
    path: '/stream-quality',
    description: 'Push quality config to Android app',
    handler: handleSetQualityRequest,
}
```

Add `readJsonBody` helper near top of file:

```typescript
import { Buffer } from 'node:buffer';

async function readJsonBody(request: IncomingMessage): Promise<unknown> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = [];
        request.on('data', chunk => chunks.push(chunk as Buffer));
        request.on('end', () => {
            try { resolve(JSON.parse(Buffer.concat(chunks).toString())); }
            catch { resolve({}); }
        });
        request.on('error', reject);
    });
}
```


Handler implementation for `handleSetQualityRequest`:

```typescript
async function handleSetQualityRequest(
    request: IncomingMessage, response: ServerResponse,
    context: RequestContext, serverContext: ServerContext,
): Promise<void> {
    const streamId = context.url.searchParams.get('id');
    const channel  = streamId ? serverContext.services.channels.getChannel(streamId) : null;
    if (!channel || !streamId) { sendJson(response, 404, { error: 'channel not found' }); return; }

    const body = await readJsonBody(request) as Partial<QualityConfig>;

    const allowed = {
        bitrate:    [16_000, 32_000, 64_000, 96_000, 128_000, 192_000],
        sampleRate: [48_000],  // fixed — only 48 kHz supported
        frameMs:    [20, 40, 60],
    };
    if (body.bitrate    && !allowed.bitrate.includes(body.bitrate))       { sendJson(response, 400, { error: 'invalid bitrate' });    return; }
    if (body.sampleRate && !allowed.sampleRate.includes(body.sampleRate)) { sendJson(response, 400, { error: 'invalid sampleRate — only 48000 Hz is supported' }); return; }
    if (body.frameMs    && !allowed.frameMs.includes(body.frameMs))       { sendJson(response, 400, { error: 'invalid frameMs' });    return; }

    const current = channel.qualityConfig ?? { bitrate: 192_000, sampleRate: 48_000, frameMs: 60, complexity: 10 };
    const next: QualityConfig = { ...current, ...body };
    channel.qualityConfig = next;

    const commandId = randomUUID();
    if (isOpen(channel.controlWs)) {
        channel.controlWs.send(JSON.stringify({ type: 'cmd', commandId, action: 'set_quality', ...next }));
        sendJson(response, 200, { ok: true, commandId, applied: next });
        return;
    }
    // Firebase fallback — reuse url field as JSON payload
    const wrote = await serverContext.services.firebase.queueFallbackCommand(
        streamId, commandId, 'set_quality', JSON.stringify(next),
    );
    sendJson(response, wrote ? 202 : 409, { ok: wrote, commandId, channel: wrote ? 'firebase' : 'offline', applied: next });
}
```

Also update `emitStatus` and `statsBroadcastTimer` to include `audioMode` and `qualityConfig`:

```typescript
function emitStatus(channel: Channel, serverContext: ServerContext): void {
    serverContext.services.channels.broadcast(channel, JSON.stringify({
        type:            'status',
        phoneConnected:  serverContext.services.channels.isPhoneConnected(channel),
        streamingActive: channel.streamingActive,
        volumeGain:      serverContext.config.serverVolumeGain,
        audioMode:       channel.audioMode,      // NEW
        qualityConfig:   channel.qualityConfig,  // NEW
    }));
}
```

Handle `audioMode` text frame inside `handleStreamSocket → socket.on('message')`:

```typescript
if (payload.type === 'audioMode') {
    channel.audioMode = (payload as any).mode ?? 'mic';
    context.requestLogger.info('Audio mode changed', { streamId: streamId.slice(0, 8), mode: channel.audioMode });
    serverContext.services.channels.broadcast(channel, text);  // forward to browsers
    return;
}
```


---

## Feature 2: Phone Call Audio Streaming

### Android Permissions Reality Check

| Audio Source | Captures | Permission | Available to regular apps |
|---|---|---|---|
| `MIC` | Microphone only | `RECORD_AUDIO` | ✅ Yes |
| `VOICE_COMMUNICATION` | Mic (VoIP-optimized, AEC on) | `RECORD_AUDIO` | ✅ Yes |
| `VOICE_CALL` | Both call directions | `CAPTURE_AUDIO_OUTPUT` | ❌ System only |
| `VOICE_DOWNLINK` | Incoming call audio | `CAPTURE_AUDIO_OUTPUT` | ❌ System only |

**Realistic scope:** capture your outgoing voice (`VOICE_COMMUNICATION`) with echo-cancellation during a call. Incoming audio is an OS-level restriction and cannot be captured by regular apps.

### End-to-End Flow

```
TelephonyCallback detects CALL_STATE_OFFHOOK
→ sends {"type":"audioMode","mode":"call"} to server
→ AudioCaptureEngine restarts with:
     AudioSource = VOICE_COMMUNICATION
     enableAec   = true
     sampleRate  = 48_000  (fixed — same as all other presets)
     opusBitrate = 32_000  (transparent for narrowband voice)
→ Server broadcasts audioMode to browsers (UI shows "📞 Call mode")
CALL_STATE_IDLE → revert to normal MIC config
→ sends {"type":"audioMode","mode":"mic"}
```

### Android Changes

#### 3. `AudioQualityConfig.kt` — add `audioSource` field + presets

```kotlin
data class AudioQualityConfig(
    // ... existing fields ...
    val audioSource: Int = android.media.MediaRecorder.AudioSource.MIC,  // NEW
)

companion object {
    val CALL = AudioQualityConfig(
        preset         = AudioQualityPreset.CALL,
        sampleRate     = 48_000,  // fixed — same as all other presets
        frameMs        = 20,
        enableAgc      = true,
        enableNs       = true,
        enableAec      = true,
        silenceGateRms = 0.0,
        opusBitrate    = 32_000,
        opusComplexity = 7,
        audioSource    = android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    )
    val LOW    = AudioQualityConfig(preset=LOW,    sampleRate=48_000, frameMs=20, opusBitrate=16_000,  opusComplexity=5, enableAgc=true,  enableNs=true,  enableAec=true,  silenceGateRms=0.0)
    val MEDIUM = AudioQualityConfig(preset=MEDIUM, sampleRate=48_000, frameMs=40, opusBitrate=64_000,  opusComplexity=7, enableAgc=false, enableNs=false, enableAec=false, silenceGateRms=0.0)
    val HIGH   = AudioQualityConfig(preset=HIGH,   sampleRate=48_000, frameMs=40, opusBitrate=128_000, opusComplexity=9, enableAgc=false, enableNs=false, enableAec=false, silenceGateRms=0.0)

    fun fromServerConfig(bitrate: Int, sampleRate: Int = 48_000, frameMs: Int, complexity: Int) =
        AudioQualityConfig(preset=HD, sampleRate=48_000, frameMs=frameMs, enableAgc=false,
            enableNs=false, enableAec=false, silenceGateRms=0.0, opusBitrate=bitrate, opusComplexity=complexity)
}
```


#### 4. New file: `data/streaming/.../audio/PhoneCallDetector.kt`

```kotlin
package com.akdevelopers.auracast.audio

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import android.util.Log

/**
 * Detects phone call state changes. Requires READ_PHONE_STATE permission.
 * Uses TelephonyCallback on API 31+, PhoneStateListener on older devices.
 */
class PhoneCallDetector(private val context: Context) {

    enum class CallState { IDLE, RINGING, IN_CALL }
    var onCallStateChanged: ((CallState) -> Unit)? = null

    private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tm.registerTelephonyCallback(context.mainExecutor,
                object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = dispatch(state)
                })
        } else {
            @Suppress("DEPRECATION")
            tm.listen(object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = dispatch(state)
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }
        Log.i("PhoneCallDetector", "started")
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            tm.listen(object : PhoneStateListener() {}, PhoneStateListener.LISTEN_NONE)
        }
    }

    private fun dispatch(state: Int) {
        val mapped = when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.IN_CALL
            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
            else                                -> CallState.IDLE
        }
        Log.d("PhoneCallDetector", "callState=$mapped")
        onCallStateChanged?.invoke(mapped)
    }
}
```

#### 5. `AudioWebSocketClient.kt` — add `sendAudioMode()`

```kotlin
fun sendAudioMode(mode: String) {
    try {
        webSocket?.send("""{"type":"audioMode","mode":"$mode"}""")
        Log.d(TAG, "sendAudioMode: $mode")
    } catch (e: Exception) {
        Log.w(TAG, "sendAudioMode failed: ${e.message}")
    }
}
```

#### 6. `CommandProcessor.kt` — handle `set_quality`

```kotlin
"set_quality" -> {
    runCatching {
        val p = JSONObject(url)   // url field carries JSON payload
        val cfg = AudioQualityConfig.fromServerConfig(
            bitrate    = p.optInt("bitrate",    192_000),
            sampleRate = p.optInt("sampleRate",  48_000),
            frameMs    = p.optInt("frameMs",         60),
            complexity = p.optInt("complexity",      10),
        )
        onQualityChange?.invoke(cfg)
        Log.i(TAG, "Quality updated: ${cfg.opusBitrate/1000}kbps ${cfg.sampleRate/1000}kHz")
    }.onFailure { Log.w(TAG, "set_quality parse error: $it") }
}
```

Add: `var onQualityChange: ((AudioQualityConfig) -> Unit)? = null`

#### 7. Streaming Service — wire everything together

```kotlin
// Wire quality changes
commandProcessor.onQualityChange = { newConfig ->
    currentQualityConfig = newConfig
    restartCapture(newConfig)
}

// Wire phone call detection
phoneCallDetector.onCallStateChanged = { state ->
    when (state) {
        CallState.IN_CALL -> {
            audioWebSocketClient.sendAudioMode("call")
            restartCapture(AudioQualityConfig.CALL)
        }
        CallState.IDLE -> {
            audioWebSocketClient.sendAudioMode("mic")
            restartCapture(currentQualityConfig)
        }
        CallState.RINGING -> { /* optionally pause stream */ }
    }
}
```

#### 8. `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

---

## New API Summary

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `POST` | `/stream-quality?id=<id>` | `{"bitrate":64000,"sampleRate":48000,"frameMs":20,"complexity":7}` | Push quality to phone (sampleRate must be 48000) |
| `GET`  | `/streams` | — | Now includes `audioMode` + `qualityConfig` per channel |

## Files Changed

| File | Change |
|------|--------|
| `server/src/services/channel-registry.ts` | `QualityConfig` type, `qualityConfig` + `audioMode` on `Channel` |
| `server/src/modules/relay-module.ts` | `POST /stream-quality`, `readJsonBody`, `audioMode` frame handler, updated `emitStatus` |
| `AudioQualityConfig.kt` | `audioSource` field, `CALL`/`LOW`/`MEDIUM`/`HIGH` presets, `fromServerConfig()` |
| `AudioWebSocketClient.kt` | `sendAudioMode()` |
| `PhoneCallDetector.kt` | **New file** |
| `CommandProcessor.kt` | `set_quality` action + `onQualityChange` callback |
| `AndroidManifest.xml` | `READ_PHONE_STATE` permission |
| Streaming Service | Wire detector + quality callback |
