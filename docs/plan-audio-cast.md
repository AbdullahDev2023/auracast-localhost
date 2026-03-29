# Plan: Audio Cast — Server-Upload & Remote Playback Control

## Goal

Add the ability for an operator to upload an audio file from the server-side web UI,
have the Android device silently download it, then play or stop it on demand — all
from the server browser, even when the app has been swiped out of recents.

---

## Overview of what this feature adds

| Layer | What is added |
|---|---|
| Server — new module | `audio-cast-module.ts` |
| Server — new service | `AudioStoreService` (in-memory / disk temp store) |
| Server — new page | `public/audio-cast.html` |
| Server — new routes | `GET /audio-cast`, `POST /audio-upload`, `GET /audio/:id`, `POST /audio-play`, `POST /audio-stop` |
| Android — domain | `play_audio` / `stop_audio` command contracts in `CommandProcessor` |
| Android — data | `AudioCastPlayer` in `:data:streaming` |
| Android — service wiring | `StreamingService.wireCommandProcessor()` extension |

No existing relay endpoints, Firebase schema, or streaming pipeline is touched.

---

## Why it will work when the app is not in recents

`StreamingService` is already:
- A `START_STICKY` foreground service that holds a `PARTIAL_WAKE_LOCK` and a `WIFI_MODE_FULL_HIGH_PERF` wifi lock.
- Kept alive by the OS as long as the foreground notification is visible (the "AuraCast — LIVE 🔴" notif).
- Auto-restarted via `WatchdogWorker` (existing `WorkManager` job) if the OS does kill it.

Because `AudioCastPlayer` will be managed **inside** `StreamingService`, it inherits all of
these guarantees. Swiping the app from recents removes the task but does **not** stop a
foreground service that holds a wake lock — Android policy protects it.

The notification already shown by `StreamingService` will be updated to reflect playback
state so the user always sees what is happening.

---

## Data flow

```
Operator browser
  │
  ├─ POST /audio-upload  ──►  AudioStoreService stores file
  │                           returns { audioId, downloadUrl }
  │
  ├─ POST /audio-play?id=<streamId>
  │     body: { audioId }
  │       │
  │       ▼
  │   relay-module handleStreamControlRequest  (existing path)
  │       │  controlWs open?
  │       ├─ YES ──► controlWs.send({ type:'cmd', action:'play_audio', url:<downloadUrl> })
  │       └─ NO  ──► firebase.queueFallbackCommand(streamId, cid, 'play_audio', downloadUrl)
  │
  └─ POST /audio-stop?id=<streamId>
        (same relay path, action:'stop_audio', url:'')

Android CommandProcessor
  ├─ 'play_audio'  ──►  onPlayAudio?.invoke(url)
  └─ 'stop_audio'  ──►  onStopAudio?.invoke()

StreamingService.wireCommandProcessor()
  ├─ onPlayAudio = { url -> audioCastPlayer.play(url) }
  └─ onStopAudio = {      -> audioCastPlayer.stop()  }

AudioCastPlayer
  ├─ download URL  ──►  temp file in cacheDir
  └─ MediaPlayer.start() / .stop()
```

---

## Server — `AudioStoreService`

**File:** `server/src/services/audio-store-service.ts`

Responsibilities:
- Accept a `Buffer` + original filename, assign a `UUID` audio-id, store under `server/.local/audio/`.
- Serve back the file bytes on `GET /audio/:id`.
- Auto-delete files after a configurable TTL (default 2 h) to avoid unbounded disk use.
- Expose `getFilePath(id)` and `exists(id)` to the module.

Key interface (TypeScript):

```ts
interface AudioEntry {
  id: string;
  originalName: string;
  mimeType: string;
  storedPath: string;
  createdAt: number;
}

class AudioStoreService {
  store(buf: Buffer, originalName: string, mimeType: string): AudioEntry;
  get(id: string): AudioEntry | undefined;
  purgeExpired(ttlMs: number): void;
}
```

`AudioStoreService` is injected into `AuraCastServices` and into `createAuraCastServer()`.

---

## Server — `audio-cast-module.ts`

**File:** `server/src/modules/audio-cast-module.ts`

Returns a `ServerModule` that registers five routes.

### Routes

#### `GET /audio-cast`
Serves `public/audio-cast.html`.

#### `POST /audio-upload`
- Parses multipart/form-data manually (uses `busboy` — already available via `@fastify`'s peer
  deps, or add `busboy` to `package.json`).
- Validates MIME type is `audio/*` and size ≤ configured max (default 50 MB).
- Stores via `AudioStoreService`.
- Returns:
  ```json
  { "ok": true, "audioId": "<uuid>", "downloadUrl": "/audio/<uuid>", "name": "track.mp3" }
  ```

#### `GET /audio/:id`
- Looks up `AudioStoreService`.
- Streams file bytes with correct `Content-Type` and `Content-Length`.
- Returns 404 if not found.

#### `POST /audio-play?id=<streamId>`
- Body: `{ "audioId": "<uuid>" }`.
- Resolves `downloadUrl` = `http(s)://<host>/audio/<audioId>`.
- Delegates to the **existing** `handleStreamControlRequest` helper already in `relay-module.ts`,
  passing `action = 'play_audio'` and `url = downloadUrl`.
  - This preserves the WebSocket-first, Firebase-fallback logic with zero code duplication.
- Returns the same response shape as `/stream-control`.

#### `POST /audio-stop?id=<streamId>`
- Delegates to same helper with `action = 'stop_audio'`, empty `url`.

The module is registered in `server/src/server/create-server.ts` alongside the existing modules.

---

## Server — `public/audio-cast.html`

Single self-contained HTML+CSS+JS page.  No build step required.

### Sections

1. **Stream selector** — fetches `GET /streams`, renders a `<select>` of connected channels.
2. **Upload card**:
   - `<input type="file" accept="audio/*">` drag-and-drop zone.
   - Progress bar (XHR `upload.onprogress`).
   - Shows file name + size after upload.
3. **Playback controls**:
   - ▶ **Play on device** button → `POST /audio-play?id=<streamId>` with `{ audioId }`.
   - ⏹ **Stop on device** button → `POST /audio-stop?id=<streamId>`.
   - Status badge: shows `idle | playing | stopped | error`.
4. **Upload history** — last 5 uploads listed with re-play buttons.

Dashboard `index.html` gets a link to `/audio-cast` added in its nav bar.

---

## Android — `CommandProcessor` extension

**File:** `data/streaming/src/main/java/com/akdevelopers/auracast/service/CommandProcessor.kt`

Add two new callbacks and handle two new actions.

```kotlin
// New callbacks
var onPlayAudio: ((url: String) -> Unit)? = null
var onStopAudio: (() -> Unit)?            = null
```

Wire them in the `when(action)` block:

```kotlin
"play_audio" -> {
    if (url.isNotBlank()) onPlayAudio?.invoke(url)
    else Log.w(TAG, "play_audio with empty url — ignored")
}
"stop_audio" -> onStopAudio?.invoke()
```

Clear them in `reset()`:

```kotlin
onPlayAudio = null
onStopAudio = null
```

---

## Android — `AudioCastPlayer`

**File:** `data/streaming/src/main/java/com/akdevelopers/auracast/audio/AudioCastPlayer.kt`

Responsibilities:
- Download audio from a URL into `context.cacheDir/audio_cast_<uuid>.<ext>` using `OkHttp`
  (already on the classpath via Firebase SDK).
- Play via `android.media.MediaPlayer` (no ExoPlayer dependency needed for this basic use).
- Request `AudioFocus` before playback so it can duck/mix with existing mic audio.
- Expose `play(url: String)` and `stop()`.
- Clean up the temp file when `stop()` or `onCompletion` fires.

```kotlin
class AudioCastPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null

    fun play(url: String) {
        stop()  // cancel any previous playback
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = downloadToCache(url)
                withContext(Dispatchers.Main) {
                    startPlayback(file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioCast download/play error: $e")
            }
        }
    }

    fun stop() {
        mediaPlayer?.run { if (isPlaying) stop(); release() }
        mediaPlayer = null
        tempFile?.delete(); tempFile = null
    }

    fun release() = stop()

    private fun downloadToCache(url: String): File { /* OkHttp GET, write to cacheDir */ }

    private fun startPlayback(file: File) {
        val mp = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnCompletionListener { stop() }
            prepare()
            start()
        }
        mediaPlayer = mp
        tempFile = file
    }
}
```

---

## Android — `StreamingService` wiring

**File:** `app/src/main/java/com/akdevelopers/auracast/service/StreamingService.kt`

Add one field and extend `wireCommandProcessor()` and `stopAll()`.

```kotlin
// New instance field
private var audioCastPlayer: AudioCastPlayer? = null
```

In `wireCommandProcessor()` (after existing wires):

```kotlin
if (audioCastPlayer == null) {
    audioCastPlayer = AudioCastPlayer(this)
}
CommandProcessor.onPlayAudio = { url -> audioCastPlayer?.play(url) }
CommandProcessor.onStopAudio = {      -> audioCastPlayer?.stop() }
```

In `stopAll()` (after `CommandProcessor.reset()`):

```kotlin
audioCastPlayer?.release()
audioCastPlayer = null
```

No other changes to `StreamingService` are required.  The existing `START_STICKY` + wake-lock
contract already guarantees the service survives recents swipe.

---

## New files summary

```
server/
  src/
    services/
      audio-store-service.ts        ← NEW
    modules/
      audio-cast-module.ts          ← NEW
  public/
    audio-cast.html                 ← NEW

data/streaming/src/main/java/com/akdevelopers/auracast/
  audio/
    AudioCastPlayer.kt              ← NEW

data/streaming/src/main/java/com/akdevelopers/auracast/
  service/
    CommandProcessor.kt             ← MODIFIED (2 callbacks + 2 when-branches + reset)

app/src/main/java/com/akdevelopers/auracast/
  service/
    StreamingService.kt             ← MODIFIED (1 field + 3 lines in wireCommandProcessor + 2 in stopAll)

server/src/server/
  create-server.ts                  ← MODIFIED (register AudioCastModule)

server/src/services/
  types.ts                          ← MODIFIED (add audioStore to AuraCastServices)
```

---

## Foreground / background guarantee checklist

| Scenario | Handled by |
|---|---|
| App visible in foreground | `StreamingService` running, `CommandProcessor` live |
| App in background (not in recents) | `START_STICKY` + `PARTIAL_WAKE_LOCK` keeps service alive |
| Device screen off | `PARTIAL_WAKE_LOCK` prevents CPU sleep |
| Service killed by OOM | `WatchdogWorker` (existing) revives it within ~15 min |
| Phone offline when command arrives | Firebase RTDB fallback queues command; delivered on reconnect |
| Audio file too large for Firebase | Command carries only the download URL, not the file itself |

---

## Firebase fallback behavior

`play_audio` and `stop_audio` use the **identical** WebSocket → Firebase fallback path already
implemented in `handleStreamControlRequest` (`relay-module.ts`).  The `url` field carries the
full download URL (`http(s)://<host>/audio/<id>`).  No Firebase schema changes are required.

---

## Environment / configuration additions

Add to `server/.env.example` and `server/src/core/env.ts`:

```
AUDIO_CAST_MAX_SIZE_MB=50      # max upload size in megabytes (default 50)
AUDIO_CAST_TTL_HOURS=2         # auto-delete uploaded files after N hours (default 2)
AUDIO_CAST_STORE_DIR=.local/audio  # storage path relative to server root
```

---

## Dependency additions

### Server
- `busboy` (multipart parser) — add to `server/package.json` if not transitively available.

### Android
- No new Gradle dependencies.  Uses `android.media.MediaPlayer` (SDK built-in) and
  `okhttp3` (already on classpath via Firebase).

---

## Contract tests to add

File: `server/test/audio-cast-contract.test.js`

| Test | Assertion |
|---|---|
| `POST /audio-upload` with valid MP3 | 200, `{ ok, audioId, downloadUrl }` |
| `POST /audio-upload` oversized file | 413 |
| `POST /audio-upload` wrong MIME type | 415 |
| `GET /audio/:id` known file | 200 binary stream |
| `GET /audio/:id` unknown id | 404 |
| `POST /audio-play?id=<missing>` | 404 channel not found |
| `POST /audio-stop?id=<missing>` | 404 channel not found |

---

## Verification steps

### Server
```powershell
cd .\server
npm run check
node -e "require('./dist/main.js')" # smoke-check compiled output
```

### Android
```powershell
.\gradlew.bat lintDebug lintRelease testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease --stacktrace
powershell -ExecutionPolicy Bypass -File .\scripts\android-smoke.ps1 -Headless
```

### Manual end-to-end
1. Start the stack: `.\scripts\start-auracast.bat`
2. Open `http://localhost:<PORT>/audio-cast` in browser.
3. Select the connected stream, upload a short MP3.
4. Tap **Play on device** — audio plays on Android.
5. Swipe the app from recents (service stays alive in notification).
6. Tap **Play on device** again — audio plays while app is not in recents.
7. Tap **Stop on device** — audio stops.

---

## Out of scope (future plans)

- Playlist / queue support.
- Volume control from server.
- Progress / seek feedback from device back to browser.
- Persistent audio library (database-backed).
