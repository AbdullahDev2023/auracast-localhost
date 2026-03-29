# AuraCast

AuraCast is a production-hardened Android-to-browser audio streaming stack. The Android app
captures Opus-encoded audio, streams it to a TypeScript Node.js relay server, and delivers it
to any browser listener via WebSocket. All relay endpoints, Firebase paths, and streaming
behaviour remain backward-compatible as new features are added.

## What is here

- **Android app** — modularized into `app`, `core`, `domain`, `data`, and `feature` layers.
- **Relay server** — TypeScript Node.js relay with pluggable server modules and health checks.
- **Ops scripts** — Android SDK CLI doctor/smoke tooling, secret sync helpers, and the local launcher.
- **CI** — server and Android quality gates with artifact uploads.

## Implemented features

| Feature | Status |
|---|---|
| Opus audio uplink → browser via WebSocket | ✅ Live |
| UDP audio transport (lower latency, LAN / VPS deployments) | ✅ Live |
| Audio Cast — upload & play audio files on device from browser | ✅ Live |
| Server-side quality control (`set_quality`, `set_bitrate`) | ✅ Live |
| Phone call streaming (VOICE_COMMUNICATION source, auto-detect) | ✅ Live |
| Mixed call capture (mic + earpiece via AudioPlaybackCapture) | ✅ Live |
| Remote command delivery (WebSocket + Firebase RTDB fallback) | ✅ Live |
| Audio playback queue, loop, pause, resume, seek, volume | ✅ Live |
| Watchdog auto-restart, wake lock, Wi-Fi lock | ✅ Live |


## Quick start

1. Sync local secrets:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\sync-local-secrets.ps1
   ```

2. Create the local server env file:

   ```powershell
   Copy-Item .\server\.env.example .\server\.env
   ```

3. Validate the Android CLI environment:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\android-doctor.ps1
   ```

4. Run the quality gates:

   ```powershell
   cd .\server; npm run check
   cd ..
   .\gradlew.bat lintDebug lintRelease testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease --stacktrace
   ```

5. Start the local stack:

   ```powershell
   .\scripts\start-auracast.bat
   ```

## Useful commands

```powershell
# Android smoke test with SDK CLI tools
powershell -ExecutionPolicy Bypass -File .\scripts\android-smoke.ps1 -CreateAvdIfMissing -Headless

# If the emulator image is missing
powershell -ExecutionPolicy Bypass -File .\scripts\android-smoke.ps1 -InstallMissingSdkPackages -CreateAvdIfMissing -Headless

# Start the server without the batch launcher
cd .\server
npm run start:local

# Build the server container (HTTP + UDP ports)
cd .\server
docker build -t auracast-server .
docker run --rm -p 3000:3000 -p 4001:4001/udp --env-file .env auracast-server
```


## Docs

- Architecture: [docs/architecture.md](./docs/architecture.md)
- Operations: [docs/operations.md](./docs/operations.md)
- Extending the app and server: [docs/extending.md](./docs/extending.md)

## Extension seams

### Android

| Seam | Purpose |
|---|---|
| `AuraCastAppGraph` | Composition root — wire new transports, factories, features |
| `AppFeature` / `FeatureRegistry` | Register app-scoped feature behaviour |
| `AudioTransport` | Swap or extend the audio uplink (WS, UDP, future QUIC) |
| `RemoteCommandSource` | Add new command delivery channels |
| `StatusPublisher` | Add new status sinks |
| `MetricsPublisher` | Add new metrics sinks |
| `AudioCaptureFactory` | Swap the mic backend (default, call, mixed, future DSP) |
| `StreamOrchestrator` | Coordinate capture + transport + commands |
| `CommandProcessor` | Add new remote command actions |
| `AudioCastPlayer` | Full audio playback engine — play, queue, loop, seek |
| `callModeSelector` | Choose mic-only vs mixed capture when a phone call starts |
| `isUdpCapable(url)` | Enable/disable UDP transport per-server-URL |

### Server

| Seam | Purpose |
|---|---|
| `ServerModule` | Register HTTP routes, WS routes, health checks, start/stop hooks |
| `HttpRouteDefinition` | Add new REST endpoints |
| `WsRouteDefinition` | Add new WebSocket paths |
| `HealthCheck` | Add new health/readiness checks |
| `AuraCastServices` | Add new injectable server services |

## Guardrails

- Keep existing relay endpoints, Firebase schema, and streaming behaviour backward-compatible.
- Keep release `StreamingService` non-exported. The debug manifest overlay exists only to support CLI smoke automation.
- Put secrets in local files or CI secrets — never in committed source.
- UDP transport falls back to WebSocket automatically for ngrok and Cloudflare Tunnel URLs.
- `PUBLIC_HOST` must be set in `server/.env` when the Android device connects through an ngrok URL, so the Audio Cast download URLs are reachable from the device.
- **Sample rate is fixed at 48 000 Hz for all audio presets (LOW, MEDIUM, HIGH, HD, CALL) and cannot be changed.** The server rejects any `set_quality` request that specifies a `sampleRate` other than 48 000. Android always uses 48 kHz regardless of what the server sends.
