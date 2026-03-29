# Extending AuraCast

## Rules for new work

- Do not change existing relay endpoints, Firebase schema, or streaming behaviour unless the feature explicitly requires a new contract.
- Prefer adding collaborators behind interfaces over adding more branching to `StreamingService`.
- Prefer new server modules and services over editing one central server file.
- Keep test-only hooks in debug-only Android source sets.
- Keep secrets outside committed source (`.secrets/`, `server/.local/`, CI secrets).

## Adding an Android feature

1. Put UI and user flow code in a `feature` module.
2. Put platform-free contracts in `:domain:*`.
3. Put Android or IO implementations in `:data:*` or `:core:*`.
4. Register new app-scoped behaviour through `FeatureRegistry` and the app composition root.
5. Expose shared runtime state through `StreamRuntimeStore` or another explicit state holder.

Use these seams first:

- `AppFeature` / `FeatureRegistry`
- `AppGraph` / `AuraCastAppGraph`
- `StreamServiceLauncher`

## Adding a new streaming capability

Choose the correct contract:

| New capability | Implement |
|---|---|
| New audio transport | `AudioTransport` |
| New remote command source | `RemoteCommandSource` |
| New status sink | `StatusPublisher` |
| New realtime metrics sink | `MetricsPublisher` |
| New audio input backend | `AudioCaptureFactory` |
| New call capture mode | Return from `callModeSelector` in `AuraCastAppGraph` |

Then wire it in `AuraCastAppGraph` using the composite adapters already in place.

Example pattern for a new transport:

1. Implement `AudioTransport` in `data/streaming`.
2. Update `RoutingAudioTransport` or `AuraCastAppGraph.createStreamOrchestrator()` to select it.
3. Verify `DefaultStreamOrchestrator` receives the same signalling behaviour.
4. Run Android lint, unit tests, and smoke flow.

## Extending the UDP transport

`RoutingAudioTransport` selects `AudioUdpTransport` when `isUdpCapable(url)` returns `true`.
To add a new transport decision (e.g. QUIC):

1. Add detection logic to `isUdpCapable` or introduce a new `isQuicCapable` companion method.
2. Implement a new `AudioTransport` class.
3. Update the routing lambda in `AuraCastAppGraph.createStreamOrchestrator()`.

`isUdpCapable` currently returns `false` for loopback hosts and the following tunnel suffixes:
`.ngrok-free.app`, `.ngrok-free.dev`, `.ngrok.io`, `.ngrok.dev`, `.trycloudflare.com`, `.workers.dev`.


## Adding a new remote command action

1. Add a callback property to `CommandProcessor` (e.g. `var onMyAction: ((String) -> Unit)? = null`).
2. Handle the new action in the `when(action)` block inside `CommandProcessor.process()`.
3. Clear the callback in `CommandProcessor.reset()`.
4. Wire the callback in `StreamingService.wireCommandProcessor()`.
5. Add the server-side command sender to the appropriate module (e.g. `relay-module` or a new module).
6. Add a contract test in `server/test/`.

All callbacks run on the main thread (enforced by `mainHandler.post`).
Commands are deduplicated by `commandId` across both WebSocket and Firebase delivery.

## Extending AudioCastPlayer

`AudioCastPlayer` already supports play, pause, resume, stop, loop(n), loopInfinite,
queueAdd, queueClear, setVolume, seekTo.

To add a new playback capability:

1. Add a public method to `AudioCastPlayer`.
2. Add a callback to `CommandProcessor` and wire it in `StreamingService.wireCommandProcessor()`.
3. Add a route handler in `audio-cast-module.ts` using `sendSimpleCommand()`.
4. Expose the route from `createAudioCastModule()`.
5. Add a contract test in `server/test/audio-cast-contract.test.js`.

## Adding a new Android module

Use this split:

- `core` for reusable platform or cross-feature infrastructure
- `domain` for contracts / state
- `data` for implementations and external integrations
- `feature` for user-facing flows

After creating the module:

1. Add it to `settings.gradle.kts`.
2. Add its `build.gradle.kts`.
3. Wire dependencies from the smallest necessary consumer.
4. Keep Android entry points in `:app` unless the component is purely library-internal.

## Adding a server service

Put reusable runtime behaviour in `server/src/services/`.

Good candidates:

- external integrations
- cross-route state
- metrics or observability helpers
- validation or rate-limiting helpers

Services are injected through `createAuraCastServer()` and declared in `AuraCastServices`
(`server/src/services/types.ts`) so modules receive them via `ServerContext.services`.

Example: `AudioStoreService` stores uploaded files, exposes `store()`, `get()`, `purgeExpired()`,
and is registered in `types.ts` as `audioStore: AudioStoreService`.

## Adding a server module

1. Create a new file in `server/src/modules/`.
2. Return a `ServerModule` with any needed `httpRoutes`, `wsRoutes`, `healthChecks`, `onStart`, `onStop`.
3. Register it in `server/src/server/create-server.ts`.
4. Add contract tests in `server/test/`.

Keep route behaviour backward-compatible:

- Preserve existing response shapes for old paths.
- Add new paths instead of mutating old contracts when possible.
- Keep structured logging and health reporting in place.

Example: `UdpRelayModule` exposes both a `ServerModule` interface (for lifecycle hooks)
and a `UdpRelayService` interface (for `relay-module` to call `registerSession()`).
The service reference is set at construction time so it is available before `onStart()`.

## Extending configuration safely

- Android runtime config: `AppConstants`, shared prefs, or Firebase Remote Config paths.
- Server runtime config: `server/.env` and `server/src/core/env.ts`.
  Add a new field to `EnvConfig`, parse it in `loadEnvConfig()`, and use it via `serverContext.config`.
- Secrets must stay outside committed source and flow through `.secrets/`, `server/.local/`, or CI secrets.


## Required verification for new work

Android:

```powershell
.\gradlew.bat lintDebug lintRelease testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease --stacktrace
powershell -ExecutionPolicy Bypass -File .\scripts\android-smoke.ps1 -Headless
```

Server:

```powershell
cd .\server
npm run check
```

If you add a new server route or WebSocket path, add or update contract tests before merging.
If you add a new `CommandProcessor` action, add a unit test covering the dedup logic.

## Worked example — adding a `crash_test` command end-to-end

### 1. Server — add the route (relay-module or a new module)

```typescript
// In relay-module.ts httpRoutes or a new debug-module.ts
{
    method: 'POST',
    path: '/crash-test?id=<streamId>',
    description: 'Send crash_test command to device',
    handler: (req, res, ctx, sc) =>
        sendSimpleCommand(req, res, ctx, sc, 'crash_test'),
}
```

### 2. Android — CommandProcessor

```kotlin
var onCrashTest: (() -> Unit)? = null

// In when(action):
"crash_test" -> onCrashTest?.invoke()

// In reset():
onCrashTest = null
```

### 3. Android — StreamingService.wireCommandProcessor()

```kotlin
CommandProcessor.onCrashTest = {
    // debug-only behaviour
    Log.w(TAG, "crash_test received")
}
```

### 4. Server contract test

```js
// server/test/server-contract.test.js
test('POST /crash-test — channel not found returns 404', async () => {
    const res = await fetch(`${BASE}/crash-test?id=nonexistent`, { method: 'POST' });
    assert.strictEqual(res.status, 404);
});
```
