# AuraCast Architecture

## Goals

- Keep existing Android, server, browser, and Firebase behaviour unchanged.
- Make new features and services additive instead of centralizing more logic in `StreamingService` or `server.js`.
- Keep the current local Windows + ngrok operator flow working while also supporting CI and containerized server deployment.

## Android layout

| Module | Responsibility |
|---|---|
| `:app` | Android entry points, manifests, resources, service wiring, debug-only smoke hooks |
| `:core:common` | App-wide constants and shared feature contracts |
| `:core:platform` | Android platform helpers, base view models, app graph contracts |
| `:core:observability` | Analytics and crash/reporting helpers |
| `:domain:streaming` | Streaming contracts and runtime state interfaces |
| `:data:streaming` | Concrete streaming orchestration, transports, Firebase sync, command bridges |
| `:feature:setup` | One-time setup flow, battery/autostart guidance, MediaProjection permission prompt |
| `:feature:stream` | Main streaming UI and view model |

## Android composition root

`AuraCastApp` owns a single `AuraCastAppGraph`.

`AuraCastAppGraph.createStreamOrchestrator()` composes the current runtime from:

- `RoutingAudioTransport` — defers the UDP vs WebSocket transport decision to connect-time.
  - If `isUdpCapable(url)` returns true (non-tunnel URL), creates `AudioUdpTransport` wrapping an `AudioWebSocketClient`.
  - Otherwise falls back to `AudioWebSocketClient` directly (WebSocket-only path).
- `ControlSocketRemoteCommandSource` → WebSocket `/control`
- `FirebaseSyncBridge` → Firebase RTDB fallback
- `CompositeRemoteCommandSource`
- `CompositeStatusPublisher`
- `CompositeMetricsPublisher`
- `DefaultAudioCaptureFactory` (default mic mode)
- `callModeSelector` — picks `MixedCallCaptureFactory` (mic + earpiece) or `DefaultAudioCaptureFactory(CALL)` based on whether a `MediaProjection` token is held in `MediaProjectionStore`.

Supporting singletons:
- `FeatureRegistry` / `AppFeature` — app-level feature registration seam.
- `StreamServiceLauncher` — app-facing adapter for starting/stopping the foreground service.


## Android streaming flow

1. `MainActivity` and `MainViewModel` talk to `StreamServiceLauncher` and `StreamRuntimeStore`.
2. `StreamingService` acts as a thin Android adapter and foreground-service shell.
3. `DefaultStreamOrchestrator` coordinates:
   - audio capture via the active `AudioCaptureFactory`
   - frame delivery via `AudioTransport` (UDP or WebSocket)
   - remote commands via `RemoteCommandSource`
   - status publication via `StatusPublisher`
   - realtime metrics via `MetricsPublisher`
4. `StreamRuntimeStore` exposes shared `status`, `isRunning`, and `isConnected` flows to the UI and workers.

## Audio transport — UDP vs WebSocket

`RoutingAudioTransport` selects the transport at connect-time based on `isUdpCapable(url)`:

- **UDP path** (`AudioUdpTransport`): low-latency Opus frames over UDP datagrams.
  Frame format: 12-byte header (version + flags + sequence + 8-byte token) + raw Opus payload.
  All WS signalling (codec config, streaming state, audio mode, udpReady/udpAck handshake) stays
  on the underlying `AudioWebSocketClient`.
- **WebSocket path** (`AudioWebSocketClient`): full binary-frame transport over `/stream`.
  Used automatically when the server URL is an ngrok, Cloudflare Tunnel, or loopback address.

`isUdpCapable(url)` returns `false` for loopback hosts and tunnel suffixes
(`.ngrok-free.app`, `.ngrok.io`, `.trycloudflare.com`, `.workers.dev`).

## Phone call streaming

`PhoneCallMonitor` (wraps `PhoneCallDetector`) delivers `onCallStarted` / `onCallEnded` events
to the orchestrator. On `onCallStarted`:

- `callModeSelector` is invoked — returns a `(AudioCaptureFactory, audioMode)` pair.
  - If `MediaProjectionStore` holds a live token and API ≥ 29: `MixedCallCaptureFactory` + `"call_mixed"`.
  - Otherwise: `DefaultAudioCaptureFactory(AudioQualityConfig.CALL)` + `"call"`.
- `AudioQualityConfig.CALL` uses `VOICE_COMMUNICATION` audio source, **48 kHz** (fixed), 32 kbps, AEC/AGC/NS on.
- `MixedCallCaptureFactory` adds `IncomingCallAudioCapture` (AudioPlaybackCapture API) and
  mixes both PCM streams via `AudioCallMixer` before Opus encoding.
- The `audioMode` string is sent to the server so the browser dashboard can show the correct label.

`MediaProjectionStore` holds the singleton `MediaProjection` token. The token is injected into
the already-running service via `ACTION_SET_MEDIA_PROJECTION`.

## Audio Cast — remote file playback

`AudioCastPlayer` (owned by `StreamingService`) supports:
play, pause, resume, stop, loop(n), loopInfinite, queueAdd, queueClear, setVolume, seekTo.

All commands arrive via `CommandProcessor` (WebSocket `/control` or Firebase RTDB fallback).
Files are downloaded to `cacheDir` via OkHttp, played via `MediaPlayer`, and cleaned up after playback.

The server side is `AudioCastModule`, which exposes:

| Route | Purpose |
|---|---|
| `GET /audio-cast` | Operator upload page |
| `POST /audio-upload` | Multipart upload; returns `{ audioId, downloadUrl }` |
| `GET /audio/<id>` | Serve stored audio file to Android device |
| `POST /audio-play?id=<streamId>` | Send `play_audio` command |
| `POST /audio-stop?id=<streamId>` | Send `stop_audio` command |
| `POST /audio-pause?id=<streamId>` | Send `pause_audio` command |
| `POST /audio-resume?id=<streamId>` | Send `resume_audio` command |
| `POST /audio-loop?id=<streamId>` | Send `loop_audio` command |
| `POST /audio-loop-infinite?id=<streamId>` | Send `loop_infinite` command |
| `POST /audio-queue-add?id=<streamId>` | Send `queue_add` command |
| `POST /audio-queue-clear?id=<streamId>` | Send `queue_clear` command |
| `POST /audio-volume?id=<streamId>` | Send `set_volume` command |
| `POST /audio-seek?id=<streamId>` | Send `seek_audio` command |

`AudioStoreService` manages in-memory metadata + disk files under `AUDIO_CAST_STORE_DIR`
(default `.local/audio`), with TTL-based auto-purge (default 2 h).


## Server layout

| Path | Responsibility |
|---|---|
| `server/src/main.ts` | Process bootstrap, `loadEnvConfig`, graceful shutdown |
| `server/src/server/create-server.ts` | Server assembly, route + WS registration, module start/stop hooks |
| `server/src/core/env.ts` | Typed env config; all settings with defaults |
| `server/src/core/contracts.ts` | `ServerModule`, `ServerContext`, `ServerRuntimeContext`, typed contracts |
| `server/src/core/http.ts` | `sendJson`, `sendFile` helpers |
| `server/src/core/logger.ts` | Structured logger factory |
| `server/src/services/channel-registry.ts` | Channel lifecycle, `QualityConfig`, `ChannelStats` |
| `server/src/services/firebase-command-service.ts` | Firebase RTDB fallback command queue |
| `server/src/services/metrics-service.ts` | Lightweight in-process counter service |
| `server/src/services/rate-limit-service.ts` | Request rate limiting |
| `server/src/services/audio-store-service.ts` | Upload storage, TTL purge, file serving |
| `server/src/services/types.ts` | `AuraCastServices`, `UdpRelayService` interfaces |

## Server module list

| Module | Routes registered |
|---|---|
| `dashboard-module` | `GET /`, `GET /player`, `GET /streams` (dashboard pages) |
| `relay-module` | `GET /streams`, `POST /stream-control`, `POST /stream-quality`, `POST /stream-bitrate`, `POST /volume`; WS `/stream`, `/control`, `/listen` |
| `operations-module` | `GET /health`, `GET /ready`, `GET /metrics`, `GET /wake` |
| `audio-cast-module` | All `/audio-*` routes above |
| `udp-relay-module` | UDP socket on `UDP_PORT` (default 4001); no HTTP routes |

## Server module model

Each backend feature is a `ServerModule` with optional:

- `httpRoutes` — `HttpRouteDefinition[]`
- `wsRoutes` — `WsRouteDefinition[]`
- `healthChecks` — `HealthCheck[]`
- `onStart(runtimeContext)` — timers, sockets, background jobs
- `onStop(runtimeContext)` — cleanup

Services are injected via `ServerContext.services` (`AuraCastServices`).

## Quality control

`POST /stream-quality?id=<streamId>` sends a `set_quality` command to the Android app.
`POST /stream-bitrate?id=<streamId>` hot-swaps the Opus bitrate without restarting the mic.

Both routes use the WebSocket `/control` channel first, then Firebase RTDB fallback.
`CommandProcessor` on Android dispatches to `onQualityChange` / `onBitrateChange` callbacks,
which call `DefaultStreamOrchestrator.applyQuality()` / `applyBitrate()`.

Allowed quality values (validated server-side):
- `bitrate`: 16 000 | 32 000 | 64 000 | 96 000 | 128 000 | 192 000 bps
- `sampleRate`: **48 000 Hz only** — fixed and not configurable; any other value is rejected with HTTP 400
- `frameMs`: 20 | 40 | 60 ms
- `complexity`: 0–10

## Shared runtime and compatibility

- Existing browser/dashboard contracts remain intact.
- Existing Firebase paths remain intact.
- Existing control actions and relay routes remain intact.
- Release Android manifest keeps `StreamingService` non-exported.
- Debug builds include a manifest overlay so `adb`-driven smoke tests can start the service.

## Configuration and secrets

- Android Firebase files are sourced from local secret copies or CI secrets.
- Server Firebase Admin credentials are loaded from (in priority order):
  1. `FIREBASE_SERVICE_ACCOUNT_JSON` env var
  2. `FIREBASE_SERVICE_ACCOUNT_PATH` env var
  3. `server/.local/serviceAccount.json`
  4. `server/config/serviceAccount.json`
- Runtime settings live in `server/.env` (see Operations doc for full variable reference).

## Deployment shape

- Local development: `scripts/start-auracast.bat` (server + ngrok).
- CI: server checks on Linux; Android gates on Windows with JDK 21.
- Container: `server/Dockerfile` + `server/.env.production.example`.
  Both port 7000 (HTTP/WS) and port 4001/udp (UDP relay) must be mapped.
