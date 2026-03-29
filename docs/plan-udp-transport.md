# Plan: Replace TCP Audio Uplink with UDP

## Goal

Replace the WebSocket (TCP) audio frame uplink path with a lightweight **UDP datagram transport**
so that Opus audio frames are delivered with lower latency and without TCP's head-of-line blocking.

Control messages, codec signalling, and browser delivery remain on WebSocket (they require
ordering and reliability). Only the high-frequency, latency-sensitive audio frame path moves to UDP.

---

## Why UDP for Audio

| Problem with TCP/WebSocket (today) | How UDP fixes it |
|---|---|
| Head-of-line blocking — one lost packet stalls all subsequent frames | Lost frames are simply skipped; jitter buffer hides the gap |
| Nagle algorithm coalesces small writes — adds ~20 ms of extra delay | Every `sendFrame()` call dispatches immediately |
| TCP retransmit + kernel buffer inflate end-to-end latency on lossy mobile paths | UDP latency is constant regardless of loss rate |
| OkHttp WebSocket has ~6 kB per-message framing overhead | UDP datagram has 8-byte header overhead |

Opus is already loss-resilient by design (FEC, PLC). A 1–2% UDP loss rate causes no
perceptible degradation. TCP retransmitting stale audio frames is strictly worse than
discarding them.

---

## Architecture After Migration

```
┌─────────────────────────────────────────────────────────────────────┐
│ Android (AuraCast app)                                              │
│                                                                     │
│  AudioRecord ──► OpusEncoder ──► AudioUdpTransport ──────────────► │  UDP :4001
│                                        │                            │
│  AudioWebSocketClient (signalling) ──► │  WebSocket /stream :443   │
│  AudioControlClient   (commands)   ──► │  WebSocket /control :443  │
└─────────────────────────────────────────────────────────────────────┘
         UDP frames ──────────────────────────────────────────────────►
         WS signalling ───────────────────────────────────────────────►
                                                                       │
┌──────────────────────────────────────────────────────────────────────▼─────┐
│ AuraCast Server (Node.js)                                                   │
│                                                                             │
│  UdpRelayModule (dgram)          RelayModule (ws)                           │
│  port 4001                       port 443 (HTTP upgrade)                    │
│                                                                             │
│  onMessage(frame, rinfo) ──────► channel.browsers.forEach(send)            │
│  session registry: token → ch    handleStreamSocket  (signalling only)      │
│  sequence tracker per channel    handleControlSocket (commands)             │
│                                  handleListenerSocket (browsers — unchanged)│
└─────────────────────────────────────────────────────────────────────────────┘
                                        │
                               WebSocket /listen
                                        │
                              ┌─────────▼──────────┐
                              │  Browser (player.html│
                              │  WebCodecs decoder   │
                              │  (unchanged)         │
                              └──────────────────────┘
```

---

## Wire Protocol

### Session Establishment (before sending UDP frames)

The phone sends a JSON message over the **existing WebSocket `/stream` connection**:

```json
{ "type": "udpReady", "udpToken": "<32-char hex token>", "streamId": "<id>" }
```

The server replies with:

```json
{ "type": "udpAck", "udpPort": 4001 }
```

From this point the phone sends audio via UDP. The WebSocket `/stream` connection stays open
for signalling only (`codec`, `streamingState`, `audioMode`, `ping/pong`).

### UDP Datagram Layout

```
 0       1       2       3       4       5       6       7
 ┌───────────────────────────────────────────────────────┐
 │  version (1)  │  flags (1)   │  sequence (uint16 BE)  │
 ├───────────────────────────────────────────────────────┤
 │              session token (8 bytes — first 8 of hex) │
 ├───────────────────────────────────────────────────────┤
 │              Opus frame payload (variable)            │
 └───────────────────────────────────────────────────────┘

version  : 0x01 (increment on breaking protocol change)
flags    : 0x00 normal | 0x01 keyframe | 0x02 last-fragment
sequence : wrapping uint16, monotone per session — server uses for ordering
token    : first 8 bytes of the session token → maps to stream channel
payload  : raw Opus frame bytes (same bytes currently sent over WebSocket binary message)
```

**Header total: 12 bytes.** Payload is the raw Opus output of `AudioCompressor`, unchanged.

### MTU Safety

At 192 kbps, 60 ms frames: frame size ≈ `192000 × 0.06 / 8 = 1440 bytes`.
Total datagram = 12 + 1440 = **1452 bytes** — just under the 1472-byte safe UDP payload for
Ethernet MTU (1500). On constrained mobile paths (LTE ~1280 MTU), use `frameMs = 40` (960 bytes)
as the safe ceiling. The existing quality negotiation (`set_quality`) handles this already.

No fragmentation is needed when `frameMs ≤ 40`. If `frameMs = 60` is used on a 48 kHz path,
the transport layer MUST fragment into two datagrams and reassemble on the server.
For simplicity in Phase 1: **cap UDP frame size at 1200 bytes; auto-downgrade to 40 ms frames
when the bitrate × frameMs product would exceed this.**

---

## NAT / Tunnel Constraints

> ⚠️ **ngrok does not support UDP.** This is the biggest operational change.

| Deployment scenario | Solution |
|---|---|
| Local LAN (dev/testing) | Direct UDP — no tunnel needed |
| VPS with public IP | Open `UDP_PORT` in firewall; use `udp://host:4001` scheme in app |
| ngrok (current prod) | **Must keep WS fallback** — see Fallback section below |
| Cloudflare Tunnel | Supports QUIC; UDP not directly forwarded — use WS fallback |
| Tailscale / WireGuard VPN | Full UDP support — ideal for private deployments |

**Recommendation for Phase 1:** implement a `useUdp` flag (off by default). Enable it only when
the server URL is a raw `host:port` (not an ngrok/Cloudflare domain). The app detects this
automatically by checking whether the resolved WS host ends in `.ngrok-free.app` or `.trycloudflare.com`.

---

## Fallback Strategy

`AudioUdpTransport` wraps `AudioWebSocketClient` as a fallback:

```
val transport: AudioTransport =
    if (udpAvailable) AudioUdpTransport(wsSignalling = wsClient)
    else              wsClient   // existing path, zero change
```

The orchestrator (`DefaultStreamOrchestrator`) needs no change — it only sees `AudioTransport`.

---

## New Files

| File | Purpose |
|---|---|
| `data/streaming/.../audio/AudioUdpTransport.kt` | `AudioTransport` impl using `java.nio.channels.DatagramChannel` (non-blocking, works in background thread). Owns a `DatagramChannel`, sequence counter, session token. Delegates all signalling calls (`sendAudioMode`, `sendCodecConfig`, `sendStreamingState`) to an injected `AudioWebSocketClient`. |
| `data/streaming/.../audio/UdpPacket.kt` | `write(seq, token, payload) → ByteArray` encoder and `parse(bytes) → UdpPacket` decoder. Pure byte manipulation, no I/O. |
| `server/src/modules/udp-relay-module.ts` | `dgram.createSocket('udp4')` on `UDP_PORT`. Maintains `Map<token, Channel>`. On each datagram: look up channel, strip header, forward raw Opus payload to `channel.browsers` over WebSocket. Handles token expiry (30-min idle). |

---

## Changed Files

| File | Change |
|---|---|
| `domain/streaming/StreamContracts.kt` | Add optional `fun supportsUdp(): Boolean = false` default method to `AudioTransport` (no breaking change to existing impl) |
| `data/streaming/.../audio/AudioWebSocketClient.kt` | Add `sendUdpReady(token)` helper that sends `{"type":"udpReady","udpToken":...}` over the stream socket. Existing behaviour unchanged. |
| `app/.../di/AuraCastAppGraph.kt` | Wire `AudioUdpTransport` when `udpAvailable`; pass existing `wsClient` as the signalling delegate |
| `server/src/core/contracts.ts` | Add `udpServer: dgram.Socket` to `ServerRuntimeContext` |
| `server/src/server/create-server.ts` | Spin up UDP socket alongside HTTP server; pass it into module `onStart()` |
| `server/src/modules/relay-module.ts` | In `handleStreamSocket`, handle new `udpReady` message type: register token → channel mapping in `UdpRelayModule`'s session registry. No frame forwarding change needed (frames no longer arrive here) |
| `server/.env.example` | Add `UDP_PORT=4001` |
| `server/src/core/env.ts` | Parse `UDP_PORT` (default `4001`) |
| `docs/operations.md` | Add "Opening the UDP port" section |

---

## Step-by-Step Implementation Plan

### Step 1 — `UdpPacket.kt`

Simple pure-Kotlin encoder/decoder for the 12-byte header + payload format defined above.
No I/O — unit-testable in isolation.

```kotlin
object UdpPacket {
    private const val HEADER_SIZE = 12

    fun encode(seq: UShort, token8: ByteArray, payload: ByteArray): ByteArray {
        val buf = ByteArray(HEADER_SIZE + payload.size)
        buf[0] = 0x01                           // version
        buf[1] = 0x00                           // flags
        buf[2] = (seq.toInt() shr 8).toByte()
        buf[3] = seq.toByte()
        token8.copyInto(buf, 4)                 // bytes 4–11
        payload.copyInto(buf, HEADER_SIZE)
        return buf
    }

    data class Parsed(val seq: UShort, val token8: ByteArray, val payload: ByteArray)

    fun parse(buf: ByteArray): Parsed? {
        if (buf.size < HEADER_SIZE) return null
        if (buf[0] != 0x01.toByte()) return null
        val seq = ((buf[2].toInt() and 0xFF shl 8) or (buf[3].toInt() and 0xFF)).toUShort()
        val token8 = buf.copyOfRange(4, 12)
        val payload = buf.copyOfRange(HEADER_SIZE, buf.size)
        return Parsed(seq, token8, payload)
    }
}
```

### Step 2 — `AudioUdpTransport.kt`

Implements `AudioTransport`. Key design decisions:

- Uses `DatagramChannel` in a single dedicated background thread (avoid Android main-thread network exceptions)
- Session token generated once per `connect()` call — a fresh 32-char hex string
- `sendFrame(data)`: encode with `UdpPacket.encode`, call `channel.send(buffer, serverAddress)`
- All signalling (`sendAudioMode`, `sendCodecConfig`, `sendStreamingState`, `connect`, `disconnect`, `reconnectNow`) delegate to the injected `AudioWebSocketClient`
- `onStatusChange` proxied from the WS client — UDP transport is "connected" only when the WS session is CONNECTED_IDLE

```kotlin
class AudioUdpTransport(
    private val wsSignalling: AudioWebSocketClient,
) : AudioTransport {

    private var datagramChannel: DatagramChannel? = null
    private var serverAddress: InetSocketAddress? = null
    private var sessionToken8: ByteArray = ByteArray(8)
    private var seq = AtomicInteger(0)
    private val sendThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "udp-send").also { it.isDaemon = true }
    }

    override var onStatusChange get() = wsSignalling.onStatusChange
        set(v) { wsSignalling.onStatusChange = v }

    override val isOpen get() = wsSignalling.isOpen

    override fun connect(serverUrl: String, context: Context) {
        // Parse host from ws[s]://host/path → host:UDP_PORT
        val token = generateToken()
        sessionToken8 = token.substring(0, 16)
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        wsSignalling.connect(serverUrl, context)
        // After WS connects, send udpReady — see AudioWebSocketClient.onOpen hook
        wsSignalling.pendingUdpToken = token
        // Open DatagramChannel to serverHost:UDP_PORT
        val host = extractHost(serverUrl)
        val port = 4001                         // read from env/config in production
        datagramChannel = DatagramChannel.open().apply { configureBlocking(false) }
        serverAddress = InetSocketAddress(host, port)
    }

    override fun sendFrame(data: ByteArray) {
        val ch = datagramChannel ?: return
        val addr = serverAddress ?: return
        val packet = UdpPacket.encode(seq.getAndIncrement().toUShort(), sessionToken8, data)
        sendThread.submit {
            try {
                ch.send(ByteBuffer.wrap(packet), addr)
            } catch (e: Exception) {
                Log.w("UdpTransport", "sendFrame failed: ${e.message}")
            }
        }
    }

    // All other AudioTransport methods delegate to wsSignalling unchanged
    override fun disconnect()                              = wsSignalling.disconnect()
    override fun reconnectNow()                            = wsSignalling.reconnectNow()
    override fun reconnectTo(url: String, ctx: Context)    = wsSignalling.reconnectTo(url, ctx)
    override fun sendStreamingState(active: Boolean)       = wsSignalling.sendStreamingState(active)
    override fun sendAudioMode(mode: String)               = wsSignalling.sendAudioMode(mode)
    override fun sendCodecConfig(sr: Int, fm: Int, br: Int) = wsSignalling.sendCodecConfig(sr, fm, br)
}
```

### Step 3 — `AudioWebSocketClient.kt` additions

Add two things:

1. `var pendingUdpToken: String? = null` — consumed in `onOpen`, sends `{"type":"udpReady","udpToken":"<token>"}` and clears itself
2. `fun sendUdpReady(token: String)` — explicit call path for testing

### Step 4 — `udp-relay-module.ts` (server)

```typescript
import * as dgram from 'node:dgram';

export function createUdpRelayModule() {
    const sessions = new Map<string, { channelId: string; lastSeen: number }>();

    return {
        name: 'udp-relay',
        register(serverContext) {
            return {
                onStart(runtimeContext) {
                    const socket = dgram.createSocket('udp4');

                    socket.on('message', (msg: Buffer, rinfo) => {
                        // Parse 12-byte header
                        if (msg.length < 12) return;
                        const token8 = msg.subarray(4, 12).toString('hex');
                        const session = sessions.get(token8);
                        if (!session) return;   // unknown session — drop silently
                        session.lastSeen = Date.now();
                        const payload = msg.subarray(12);
                        const channel = runtimeContext.services.channels.getChannel(session.channelId);
                        if (!channel) return;
                        channel.stats.framesRelayed += 1;
                        channel.stats.bytesRelayed += payload.length;
                        for (const browser of channel.browsers) {
                            if (browser.readyState === WebSocket.OPEN) {
                                browser.send(payload, { binary: true });
                            }
                        }
                    });

                    socket.bind(runtimeContext.config.udpPort);
                    runtimeContext.udpServer = socket;

                    // Session expiry — purge tokens idle > 30 min
                    setInterval(() => {
                        const cutoff = Date.now() - 30 * 60 * 1000;
                        for (const [k, v] of sessions) {
                            if (v.lastSeen < cutoff) sessions.delete(k);
                        }
                    }, 5 * 60 * 1000);
                },
            };
        },
        // Called from relay-module when a "udpReady" WS message arrives
        registerSession(token: string, channelId: string) {
            const token8 = token.substring(0, 16);
            sessions.set(token8, { channelId, lastSeen: Date.now() });
        },
    };
}
```

### Step 5 — Wire `relay-module.ts` to handle `udpReady`

In `handleStreamSocket`, inside the `socket.on('message')` handler, add:

```typescript
if (payload.type === 'udpReady') {
    const token = (payload as { type: string; udpToken?: string }).udpToken ?? '';
    if (token.length >= 16) {
        serverContext.services.udpRelay.registerSession(token, streamId);
        socket.send(JSON.stringify({ type: 'udpAck', udpPort: serverContext.config.udpPort }));
    }
    return;
}
```

### Step 6 — `AuraCastAppGraph.kt` wiring

```kotlin
private val wsClient = AudioWebSocketClient()

private val audioTransport: AudioTransport by lazy {
    if (isUdpCapable()) AudioUdpTransport(wsSignalling = wsClient)
    else                wsClient
}

private fun isUdpCapable(): Boolean {
    // Disable UDP for ngrok/Cloudflare tunnel URLs automatically
    val host = resolvedServerUrl.removePrefix("wss://").removePrefix("ws://").substringBefore("/")
    val tunnelSuffixes = listOf(".ngrok-free.app", ".ngrok.io", ".trycloudflare.com")
    return tunnelSuffixes.none { host.endsWith(it) }
}
```

---

## Migration Phases

### Phase 1 — Infrastructure + fallback (no user-visible change)
- Implement `UdpPacket.kt` and unit tests
- Implement `AudioUdpTransport.kt` behind the `isUdpCapable()` flag (off for ngrok URLs)
- Add `udpReady` / `udpAck` handshake to WS signalling
- Implement `udp-relay-module.ts` on server
- Integration test on LAN with `UDP_PORT=4001` open

### Phase 2 — Enable for direct-IP deployments
- Set `isUdpCapable()` to `true` for non-tunnel server URLs
- A/B test latency: compare `audioCtx.currentTime` jitter in player.html with WS vs UDP source
- Add `"transport":"udp"` to the stats broadcast so player.html can show the active transport

### Phase 3 — MTU-adaptive frame splitting (optional)
- Implement two-datagram fragmentation for 60 ms / 192 kbps frames when MTU probe detects < 1460-byte path
- Server reassembly before forwarding to browsers

---

## Limitations & Known Risks

| Risk | Mitigation |
|---|---|
| ngrok and most reverse proxies block UDP | Fallback to WS automatically when tunnel URL detected |
| Android `DatagramChannel` blocked by aggressive battery/doze optimization | Send via `sendThread`; hold a `PARTIAL_WAKE_LOCK` while streaming (already done by `StreamingService`) |
| UDP packets reordered across cell towers | Server-side per-channel sequence tracker; forward in-order with 20 ms hold window |
| Session token brute-force (8-byte key space) | 64-bit token makes guessing infeasible in practice; tie to source IP on server for extra safety |
| Opus frames exceeding MTU at high bitrates | Automatic downgrade to 40 ms frames when `bitrate × frameMs / 8 > 1200` bytes |
| Double-frame delivery on reconnect | Sequence number dedup on server: discard seq ≤ lastSeen per session |

---

## Files Summary

### New Files

| File | Module |
|---|---|
| `data/streaming/.../audio/AudioUdpTransport.kt` | `data:streaming` |
| `data/streaming/.../audio/UdpPacket.kt` | `data:streaming` |
| `server/src/modules/udp-relay-module.ts` | `server` |

### Changed Files

| File | Change |
|---|---|
| `domain/streaming/StreamContracts.kt` | `fun supportsUdp()` default on `AudioTransport` |
| `data/streaming/.../audio/AudioWebSocketClient.kt` | `pendingUdpToken` field + `udpReady` send in `onOpen` |
| `app/.../di/AuraCastAppGraph.kt` | Conditional `AudioUdpTransport` wiring |
| `server/src/core/contracts.ts` | `udpServer` on `ServerRuntimeContext` |
| `server/src/server/create-server.ts` | Spin up UDP socket |
| `server/src/modules/relay-module.ts` | Handle `udpReady` message; pass `udpRelay` service ref |
| `server/src/core/env.ts` | Parse `UDP_PORT` |
| `server/.env.example` | `UDP_PORT=4001` |
| `docs/operations.md` | "Opening UDP port" section |
