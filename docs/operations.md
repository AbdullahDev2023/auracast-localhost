# AuraCast Operations

## Prerequisites

- Windows with Android Studio installed
- Android SDK command-line tools
- Node.js 20+
- Docker (optional, for server container builds)
- ngrok for the existing remote relay workflow

## Local secret layout

Create a local `.secrets` directory with this structure:

```text
.secrets/
  android/
    google-services.release.json
    google-services.debug.json
  server/
    serviceAccount.json
```

Then sync those files into the expected local destinations:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\sync-local-secrets.ps1
```

That copies to:

- `app\google-services.json`
- `app\src\debug\google-services.json`
- `server\.local\serviceAccount.json`

## Server env setup

```powershell
Copy-Item .\server\.env.example .\server\.env
```

### Complete env variable reference

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `7000` | HTTP / WebSocket server port |
| `UDP_PORT` | `4001` | UDP relay port for audio frames |
| `FIREBASE_DB_URL` | *(hardcoded fallback)* | Firebase RTDB URL |
| `FIREBASE_DB_SECRET` | — | Firebase legacy secret for fallback commands |
| `FIREBASE_SERVICE_ACCOUNT_PATH` | auto-detected | Path to service account JSON |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | — | Inline service account JSON (CI use) |
| `PUBLIC_HOST` | — | Public URL/host the Android device can reach, e.g. `abc123.ngrok-free.app`. **Must be set when using ngrok** so Audio Cast download URLs are device-reachable. |
| `SERVER_VOLUME_GAIN` | `3.0` | Default browser audio gain (0–5) |
| `LOG_LEVEL` | `INFO` | `DEBUG` \| `INFO` \| `WARN` \| `ERROR` |
| `LOG_FORMAT` | `pretty` | `pretty` \| `json` |
| `RATE_LIMIT_ENABLED` | `false` | Enable HTTP rate limiting |
| `RATE_WINDOW_MS` | `10000` | Rate limit window |
| `MAX_REQUESTS_PER_WINDOW` | `120` | Max requests per window per IP |
| `SHUTDOWN_TIMEOUT_MS` | `10000` | Graceful shutdown timeout |
| `WS_PING_INTERVAL_MS` | `70000` | WebSocket heartbeat interval |
| `STALE_THRESHOLD_MS` | `90000` | Terminate phone socket after this idle time |
| `STALE_CHECK_MS` | `15000` | How often to check for stale sockets |
| `STATS_INTERVAL_MS` | `2000` | How often to push stats frames to browsers |
| `CHANNEL_GRACE_MS` | `700000` | Grace period before removing an empty channel |
| `AUDIO_CAST_MAX_SIZE_MB` | `50` | Max upload size for Audio Cast files |
| `AUDIO_CAST_TTL_HOURS` | `2` | Auto-delete uploaded audio after N hours |
| `AUDIO_CAST_STORE_DIR` | `.local/audio` | Storage path relative to server root |


## Android SDK CLI validation

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\android-doctor.ps1
```

What it checks:

- project root
- resolved SDK root
- resolved `JAVA_HOME`
- `sdkmanager`
- `adb`
- `avdmanager`
- compile SDK platform
- installed build-tools
- backup-style SDK folders such as `android-36.1.bak`

The `.bak` platform warning is non-blocking but should be cleaned up on the machine.

## Quality gates

Server:

```powershell
cd .\server
npm run check
```

Android:

```powershell
.\gradlew.bat lintDebug lintRelease testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease --stacktrace
```

## Android smoke test

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\android-smoke.ps1 -CreateAvdIfMissing -Headless
```

If the system image is missing:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\android-smoke.ps1 -InstallMissingSdkPackages -CreateAvdIfMissing -Headless
```

What the smoke flow verifies:

- emulator creation or reuse
- APK build and install
- setup activity launch
- foreground service start
- pause mic action
- resume mic action
- full service stop

Manual follow-up still recommended for:

- reconnect behaviour against a live relay
- watchdog recovery
- Firebase-triggered remote commands
- Audio Cast end-to-end (upload → play on device)
- Mixed call capture on physical hardware

## Local run flow

One-click operator path:

```powershell
.\scripts\start-auracast.bat
```

That launcher:

- loads `server\.env`
- starts the TypeScript relay via `npm run start:local`
- starts ngrok
- opens `http://localhost:7000`

Manual server run:

```powershell
cd .\server
npm run start:local
```

## Container run

Build:

```powershell
cd .\server
docker build -t auracast-server .
```

Run (HTTP + UDP):

```powershell
docker run --rm -p 7000:7000 -p 4001:4001/udp --env-file .env auracast-server
```

Health endpoints:

- readiness: `GET /ready`
- compatibility health: `GET /health`

## Opening the UDP port

The audio relay uses a UDP socket on `UDP_PORT` (default **4001**) in addition to the
HTTP/WebSocket server on `PORT`. Both must be reachable from the Android device.

| Deployment | Action required |
|---|---|
| **Local LAN** | No firewall changes needed — Android and server share the same network |
| **VPS / cloud VM** | Open inbound **UDP 4001** in your firewall / cloud security-group rules |
| **Docker** | Add `-p 4001:4001/udp` to `docker run`, or `"4001:4001/udp"` in Compose |
| **ngrok** | ⚠️ ngrok does not support UDP — app falls back to WebSocket automatically |
| **Cloudflare Tunnel** | UDP not forwarded — same automatic WS fallback as ngrok |
| **Tailscale / WireGuard** | Full UDP support, no extra steps needed |

The Android app detects tunnel URLs automatically (`.ngrok-free.app`, `.ngrok.io`,
`.trycloudflare.com`, `.workers.dev`) and uses WebSocket-only mode for those hosts.

To verify the port is open from any host on the network:

```bash
echo -n "test" | nc -u <server-ip> 4001
```

The server log will show a UDP socket error or silently drop the undersized packet
(< 12 bytes), but the absence of a connection-refused error confirms the port is open.

To change the port: set `UDP_PORT=<port>` in `server/.env` and restart the server.
The Android app reads the port from the `udpAck` server response — no app change needed.


## Audio Cast setup

Audio Cast lets an operator upload an audio file from the browser and play/control it
on the Android device remotely — even when the app is swiped from recents.

1. Set `PUBLIC_HOST` in `server/.env` to your machine's LAN IP or ngrok URL, e.g.:
   ```
   PUBLIC_HOST=192.168.1.42:7000
   ```
   Without this, the download URL sent to the Android device will be `localhost`,
   which the device cannot reach. If using ngrok, set it to your ngrok hostname:
   ```
   PUBLIC_HOST=abc123.ngrok-free.app
   ```

2. Open `http://localhost:7000/audio-cast` in the operator browser.

3. Select the connected stream, upload an audio file (MP3, AAC, OGG, WAV — up to
   `AUDIO_CAST_MAX_SIZE_MB`, default 50 MB).

4. Use the playback controls to play, pause, stop, loop, or queue tracks on the device.

Uploaded files are automatically deleted after `AUDIO_CAST_TTL_HOURS` (default 2 hours).
Storage location: `server/.local/audio/` (configurable via `AUDIO_CAST_STORE_DIR`).

## MediaProjection permission (mixed call capture)

To capture both sides of a phone call (mic + earpiece), the user must grant a
MediaProjection permission before the call starts:

1. A prompt appears in the setup flow or as a runtime dialog when call mode is first used.
2. The user approves the Android system dialog ("AuraCast will capture your screen").
3. The granted token is stored in `MediaProjectionStore` and used for the duration of
   the service session.
4. On Android 14+, `StreamingService` re-declares `foregroundServiceType=microphone|mediaProjection`.

If the user denies or the device is below API 29, the app falls back to mic-only call mode
(existing behaviour — no earpiece capture).

## CI secrets

GitHub Actions expects:

- `GOOGLE_SERVICES_JSON_RELEASE` — required
- `GOOGLE_SERVICES_JSON_DEBUG` — optional (falls back to release config)

The Android CI job runs on `windows-latest` with JDK 21. It restores the Firebase config
files, runs `android-doctor.ps1`, then runs the full Gradle quality gates.
Lint and test reports are uploaded as artifacts. Both debug and release APKs are archived.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `JAVA_HOME is not set` | Run the Android scripts instead of raw Gradle, or set `JAVA_HOME` manually |
| `google-services.json` package mismatch | Resync debug and release Firebase config files |
| `adb` sees no devices | Run the smoke script with `-CreateAvdIfMissing` or connect a physical device |
| `Firebase Admin not configured` | Confirm `server\.local\serviceAccount.json` exists or set `FIREBASE_SERVICE_ACCOUNT_JSON` |
| `lintDebug` fails after debug-only test changes | Keep release behaviour unchanged; use debug manifest overlays for test-only adb hooks |
| Audio Cast download fails on device | Set `PUBLIC_HOST` in `server/.env` to a device-reachable address |
| UDP frames not arriving | Check that UDP port 4001 is open inbound; verify the server URL is not a tunnel URL |
| Call capture is silent (earpiece) | OEM restriction (Samsung One UI, MIUI); app falls back to mic-only automatically |
| `MediaProjection` dialog does not appear | Grant `FOREGROUND_SERVICE_MEDIA_PROJECTION` in manifest; check API ≥ 29 |
