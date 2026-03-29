import { randomUUID } from 'node:crypto';
import type { IncomingMessage, ServerResponse } from 'node:http';
import WebSocket from 'ws';
import { sendJson } from '../core/http';
import type {
    RequestContext,
    ServerContext,
    ServerModule,
    ServerRuntimeContext,
    WebSocketContext,
} from '../core/contracts';
import type { Channel } from '../services/channel-registry';
import type { QualityConfig } from '../services/channel-registry';

type LiveWebSocket = WebSocket & { isAlive?: boolean };

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

function isOpen(socket: WebSocket | null | undefined): socket is WebSocket {
    return socket !== null && socket !== undefined && socket.readyState === WebSocket.OPEN;
}

function rawDataSize(data: WebSocket.RawData): number {
    if (Buffer.isBuffer(data)) {
        return data.byteLength;
    }
    if (Array.isArray(data)) {
        return data.reduce((total, item) => total + item.byteLength, 0);
    }
    return data.byteLength;
}

function rawDataToText(data: WebSocket.RawData): string {
    if (Buffer.isBuffer(data)) {
        return data.toString('utf-8');
    }
    if (Array.isArray(data)) {
        return Buffer.concat(data).toString('utf-8');
    }
    return Buffer.from(data).toString('utf-8');
}

function emitStatus(channel: Channel, serverContext: ServerContext): void {
    serverContext.services.channels.broadcast(channel, JSON.stringify({
        type: 'status',
        phoneConnected: serverContext.services.channels.isPhoneConnected(channel),
        streamingActive: channel.streamingActive,
        volumeGain: serverContext.config.serverVolumeGain,
        audioMode: channel.audioMode,
        qualityConfig: channel.qualityConfig,
    }));
}

function attachPongActivity(socket: WebSocket, streamId: string, serverContext: ServerContext): void {
    const liveSocket = socket as LiveWebSocket;
    socket.on('pong', () => {
        const channel = serverContext.services.channels.getChannel(streamId);
        if (!channel) {
            return;
        }
        liveSocket.isAlive = true;
        if (channel.phoneWs === socket) {
            channel.stats.lastActivityMs = Date.now();
        }
    });
}

function handleStreamSocket(
    socket: WebSocket,
    _request: IncomingMessage,
    context: WebSocketContext,
    serverContext: ServerContext,
): void {
    const streamId = context.url.searchParams.get('id');
    if (!streamId) {
        socket.close(1008, 'Missing ?id=');
        return;
    }

    const displayName = context.url.searchParams.get('name') || '';
    const channel = serverContext.services.channels.getOrCreateChannel(streamId, displayName);
    serverContext.services.channels.cancelGrace(streamId);

    if (isOpen(channel.phoneWs)) {
        context.requestLogger.info('Replacing stale phone stream socket', { streamId: streamId.slice(0, 8) });
        channel.phoneWs.close(1000, 'Replaced');
    }

    (socket as LiveWebSocket).isAlive = true;
    attachPongActivity(socket, streamId, serverContext);

    channel.phoneWs = socket;
    channel.stats.connectedAt = Date.now();
    channel.stats.disconnectedAt = null;
    channel.stats.lastActivityMs = Date.now();
    channel.stats.framesRelayed = 0;
    channel.stats.bytesRelayed = 0;
    channel.streamingActive = false;
    if (displayName) {
        channel.displayName = displayName;
    }

    context.requestLogger.info('Phone stream connected', {
        streamId: streamId.slice(0, 8),
        displayName: channel.displayName,
    });
    emitStatus(channel, serverContext);

    socket.on('message', (data, isBinary) => {
        channel.stats.lastActivityMs = Date.now();

        if (isBinary) {
            channel.stats.framesRelayed += 1;
            channel.stats.bytesRelayed += rawDataSize(data);
            serverContext.services.metrics.increment('audioFrames');
            for (const browser of channel.browsers) {
                if (browser.readyState === WebSocket.OPEN) {
                    browser.send(data, { binary: true });
                }
            }
            return;
        }

        const text = rawDataToText(data);
        if (text === '{"type":"ping"}') {
            socket.send('{"type":"pong"}');
            return;
        }

        try {
            const payload = JSON.parse(text) as { type?: string; active?: boolean };
            if (payload.type === 'codec') {
                channel.codecConfig = payload;
                serverContext.services.channels.broadcast(channel, text);
                return;
            }

            if (payload.type === 'streamingState') {
                channel.streamingActive = Boolean(payload.active);
                context.requestLogger.info('Streaming state changed', {
                    streamId: streamId.slice(0, 8),
                    active: channel.streamingActive,
                });
                emitStatus(channel, serverContext);
                return;
            }

            if (payload.type === 'audioMode') {
                channel.audioMode = (payload as { type: string; mode?: string }).mode ?? 'mic';
                context.requestLogger.info('Audio mode changed', {
                    streamId: streamId.slice(0, 8),
                    mode: channel.audioMode,
                });
                serverContext.services.channels.broadcast(channel, text);
                emitStatus(channel, serverContext);
                return;
            }

            if (payload.type === 'udpReady') {
                const token = (payload as { type: string; udpToken?: string }).udpToken ?? '';
                if (token.length >= 16) {
                    serverContext.services.udpRelay.registerSession(token, streamId);
                    socket.send(JSON.stringify({
                        type: 'udpAck',
                        udpPort: serverContext.config.udpPort,
                    }));
                    context.requestLogger.info('UDP session registered', {
                        streamId: streamId.slice(0, 8),
                        token: token.slice(0, 8),
                    });
                }
                return;
            }
        } catch {
            serverContext.services.channels.broadcast(channel, text);
        }
    });

    socket.on('close', code => {
        if (channel.phoneWs !== socket) {
            return;
        }
        channel.phoneWs = null;
        channel.streamingActive = false;
        channel.stats.disconnectedAt = Date.now();
        context.requestLogger.info('Phone stream disconnected', {
            streamId: streamId.slice(0, 8),
            code,
        });
        emitStatus(channel, serverContext);
        if (!serverContext.services.channels.isPhoneConnected(channel)) {
            serverContext.services.channels.scheduleGrace(streamId);
        }
    });

    socket.on('error', error => {
        context.requestLogger.error('Phone stream socket error', {
            streamId: streamId.slice(0, 8),
            error: error.message,
        });
    });
}

function handleControlSocket(
    socket: WebSocket,
    _request: IncomingMessage,
    context: WebSocketContext,
    serverContext: ServerContext,
): void {
    const streamId = context.url.searchParams.get('id');
    if (!streamId) {
        socket.close(1008, 'Missing ?id=');
        return;
    }

    const displayName = context.url.searchParams.get('name') || '';
    const channel = serverContext.services.channels.getOrCreateChannel(streamId, displayName);
    serverContext.services.channels.cancelGrace(streamId);

    if (isOpen(channel.controlWs)) {
        context.requestLogger.info('Replacing stale control socket', { streamId: streamId.slice(0, 8) });
        channel.controlWs.close(1000, 'Replaced');
    }

    (socket as LiveWebSocket).isAlive = true;
    attachPongActivity(socket, streamId, serverContext);

    channel.controlWs = socket;
    if (displayName) {
        channel.displayName = displayName;
    }

    context.requestLogger.info('Control socket connected', {
        streamId: streamId.slice(0, 8),
        displayName: channel.displayName,
    });

    socket.send(JSON.stringify({ type: 'welcome', status: 'ready' }));
    emitStatus(channel, serverContext);

    socket.on('message', rawData => {
        try {
            const payload = JSON.parse(rawDataToText(rawData)) as { type?: string; status?: string };
            if (payload.type === 'ping') {
                socket.send('{"type":"pong"}');
                return;
            }
            if (payload.type === 'statusUpdate') {
                channel.streamingActive = payload.status === 'STREAMING';
                emitStatus(channel, serverContext);
            }
        } catch {
            // Intentionally ignored to preserve the previous permissive behavior.
        }
    });

    socket.on('close', code => {
        if (channel.controlWs !== socket) {
            return;
        }
        channel.controlWs = null;
        channel.streamingActive = false;
        context.requestLogger.info('Control socket disconnected', {
            streamId: streamId.slice(0, 8),
            code,
        });
        emitStatus(channel, serverContext);
        if (!serverContext.services.channels.isPhoneConnected(channel)) {
            serverContext.services.channels.scheduleGrace(streamId);
        }
    });

    socket.on('error', error => {
        context.requestLogger.error('Control socket error', {
            streamId: streamId.slice(0, 8),
            error: error.message,
        });
    });
}

function handleListenerSocket(
    socket: WebSocket,
    _request: IncomingMessage,
    context: WebSocketContext,
    serverContext: ServerContext,
): void {
    const streamId = context.url.searchParams.get('id');
    if (!streamId) {
        socket.close(1008, 'Missing ?id=');
        return;
    }

    const channel = serverContext.services.channels.getOrCreateChannel(streamId);
    serverContext.services.channels.cancelGrace(streamId);
    channel.browsers.add(socket);

    // Keep browser listener alive through the heartbeat timer.
    // The heartbeat marks every socket isAlive=false then pings; a pong sets it back
    // to true. Without this, browser sockets are terminated after 2 × wsPingIntervalMs
    // (~60 s by default) because isAlive never gets reset for listener sockets.
    (socket as LiveWebSocket).isAlive = true;
    socket.on('pong', () => { (socket as LiveWebSocket).isAlive = true; });

    context.requestLogger.info('Browser listener connected', {
        streamId: streamId.slice(0, 8),
        listeners: channel.browsers.size,
    });

    socket.send(JSON.stringify({
        type: 'status',
        phoneConnected: serverContext.services.channels.isPhoneConnected(channel),
        streamingActive: channel.streamingActive,
        volumeGain: serverContext.config.serverVolumeGain,
        audioMode: channel.audioMode,
        qualityConfig: channel.qualityConfig,
    }));

    if (channel.codecConfig) {
        socket.send(JSON.stringify(channel.codecConfig));
    }

    socket.on('close', () => {
        channel.browsers.delete(socket);
        context.requestLogger.info('Browser listener disconnected', {
            streamId: streamId.slice(0, 8),
            listeners: channel.browsers.size,
        });
        if (!serverContext.services.channels.isPhoneConnected(channel)) {
            serverContext.services.channels.scheduleGrace(streamId);
        }
    });

    socket.on('error', error => {
        context.requestLogger.error('Browser listener socket error', {
            streamId: streamId.slice(0, 8),
            error: error.message,
        });
    });
}

async function handleStreamControlRequest(
    _request: IncomingMessage,
    response: ServerResponse,
    context: RequestContext,
    serverContext: ServerContext,
): Promise<void> {
    const streamId = context.url.searchParams.get('id');
    const action = context.url.searchParams.get('action');
    const payloadUrl = context.url.searchParams.get('url') || '';
    const channel = streamId ? serverContext.services.channels.getChannel(streamId) : null;
    const commandId = randomUUID();

    if (!channel || !streamId) {
        sendJson(response, 404, { error: 'channel not found' });
        return;
    }

    if (isOpen(channel.controlWs)) {
        try {
            channel.controlWs.send(JSON.stringify({
                type: 'cmd',
                commandId,
                action,
                url: payloadUrl,
                source: 'websocket',
            }));
            serverContext.services.metrics.increment('commandsSent');
            sendJson(response, 200, { ok: true, commandId, channel: 'websocket', action });
            return;
        } catch (error) {
            context.requestLogger.error('controlWs send failed; falling back to Firebase', {
                streamId: streamId.slice(0, 8),
                error: error instanceof Error ? error.message : String(error),
            });
        }
    }

    const wrote = await serverContext.services.firebase.queueFallbackCommand(streamId, commandId, action || '', payloadUrl);
    if (wrote) {
        serverContext.services.metrics.increment('commandsSent');
        sendJson(response, 202, {
            ok: true,
            commandId,
            channel: 'firebase',
            action,
            note: 'phone offline — command queued in Firebase RTDB',
        });
        return;
    }

    sendJson(response, 409, {
        error: 'phone offline',
        hint: 'set FIREBASE_DB_SECRET in server/.env to enable queued commands',
    });
}

/**
 * POST /volume
 * Body: { "gain": 1.5 }   — float, 0 – 5
 *
 * Updates serverVolumeGain in memory and broadcasts a status message to every
 * connected browser so all open player tabs update their gain node simultaneously.
 * The value persists until the server restarts (set SERVER_VOLUME_GAIN in .env
 * for a permanent default).
 */
async function handleSetVolumeRequest(
    request: IncomingMessage,
    response: ServerResponse,
    _context: RequestContext,
    serverContext: ServerContext,
): Promise<void> {
    const body = await readJsonBody(request) as { gain?: unknown };
    const gain = typeof body.gain === 'number' ? body.gain : Number(body.gain);

    if (!Number.isFinite(gain) || gain < 0 || gain > 5) {
        sendJson(response, 400, { error: 'invalid gain — must be a finite number between 0 and 5' });
        return;
    }

    // Mutate the shared config object so every subsequent stats/status broadcast
    // carries the new value too.
    serverContext.config.serverVolumeGain = gain;

    // Immediately push a status frame to every channel's browser listeners.
    for (const channel of serverContext.services.channels.values()) {
        emitStatus(channel, serverContext);
    }

    sendJson(response, 200, { ok: true, gain });
}

const ALLOWED_BITRATES    = [16_000, 32_000, 64_000, 96_000, 128_000, 192_000];
const ALLOWED_SAMPLE_RATES = [48_000];  // fixed — only 48 kHz is supported
const ALLOWED_FRAME_MS    = [20, 40, 60];

/**
 * POST /stream-bitrate?id=<streamId>
 * Body: { "bps": 32000 }
 *
 * Hot-swaps the Opus encoding bitrate on the Android app without touching the
 * 48 kHz HD capture session — no mic restart, no audio glitch.
 * Allowed values: 16000 | 32000 | 64000 | 128000
 */
const BITRATE_LADDER = [16_000, 32_000, 64_000, 128_000];

async function handleSetBitrateRequest(
    request: IncomingMessage,
    response: ServerResponse,
    context: RequestContext,
    serverContext: ServerContext,
): Promise<void> {
    const streamId = context.url.searchParams.get('id');
    const channel  = streamId ? serverContext.services.channels.getChannel(streamId) : null;
    if (!channel || !streamId) {
        sendJson(response, 404, { error: 'channel not found' });
        return;
    }

    const body = await readJsonBody(request) as { bps?: unknown };
    const bps  = typeof body.bps === 'number' ? body.bps : Number(body.bps);

    if (!BITRATE_LADDER.includes(bps)) {
        sendJson(response, 400, {
            error: `invalid bps — allowed: ${BITRATE_LADDER.join(', ')}`,
        });
        return;
    }

    const commandId = randomUUID();

    if (isOpen(channel.controlWs)) {
        channel.controlWs.send(JSON.stringify({
            type: 'cmd',
            commandId,
            action: 'set_bitrate',
            url: String(bps),   // CommandProcessor reads url field as the bitrate string
        }));
        // Track the current bitrate so new browser listeners can be informed.
        if (channel.qualityConfig) {
            channel.qualityConfig = { ...channel.qualityConfig, bitrate: bps };
        }
        serverContext.services.metrics.increment('commandsSent');
        sendJson(response, 200, { ok: true, commandId, channel: 'websocket', bps });
        return;
    }

    // Firebase fallback — url field carries the plain integer string
    const wrote = await serverContext.services.firebase.queueFallbackCommand(
        streamId, commandId, 'set_bitrate', String(bps),
    );
    if (wrote) {
        serverContext.services.metrics.increment('commandsSent');
        sendJson(response, 202, {
            ok: true, commandId, channel: 'firebase', bps,
            note: 'phone offline — bitrate command queued in Firebase RTDB',
        });
        return;
    }

    sendJson(response, 409, {
        error: 'phone offline',
        hint: 'set FIREBASE_DB_SECRET in server/.env to enable queued commands',
    });
}

async function handleSetQualityRequest(
    request: IncomingMessage,
    response: ServerResponse,
    context: RequestContext,
    serverContext: ServerContext,
): Promise<void> {
    const streamId = context.url.searchParams.get('id');
    const channel  = streamId ? serverContext.services.channels.getChannel(streamId) : null;
    if (!channel || !streamId) {
        sendJson(response, 404, { error: 'channel not found' });
        return;
    }

    const body = await readJsonBody(request) as Partial<QualityConfig>;

    if (body.bitrate    !== undefined && !ALLOWED_BITRATES.includes(body.bitrate))       { sendJson(response, 400, { error: `invalid bitrate — allowed: ${ALLOWED_BITRATES.join(', ')}` });        return; }
    if (body.sampleRate !== undefined && !ALLOWED_SAMPLE_RATES.includes(body.sampleRate)) { sendJson(response, 400, { error: `invalid sampleRate — allowed: ${ALLOWED_SAMPLE_RATES.join(', ')}` }); return; }
    if (body.frameMs    !== undefined && !ALLOWED_FRAME_MS.includes(body.frameMs))        { sendJson(response, 400, { error: `invalid frameMs — allowed: ${ALLOWED_FRAME_MS.join(', ')}` });        return; }
    if (body.complexity !== undefined && (body.complexity < 0 || body.complexity > 10))  { sendJson(response, 400, { error: 'invalid complexity — must be 0–10' });                               return; }

    const defaults: QualityConfig = { bitrate: 192_000, sampleRate: 48_000, frameMs: 60, complexity: 10 };
    const current = channel.qualityConfig ?? defaults;
    const next: QualityConfig = { ...current, ...body, sampleRate: 48_000 };  // sampleRate always locked to 48 kHz
    channel.qualityConfig = next;

    const commandId = randomUUID();

    if (isOpen(channel.controlWs)) {
        // IMPORTANT: AudioControlClient on Android only reads the `url` field from cmd messages.
        // CommandProcessor.set_quality handler parses `url` as a JSON string to get the params.
        // Spreading params as top-level fields (old behaviour) meant `url` was always ""
        // and the quality change silently failed with a JSONObject parse error on the device.
        channel.controlWs.send(JSON.stringify({
            type: 'cmd', commandId, action: 'set_quality',
            url: JSON.stringify({ bitrate: next.bitrate, sampleRate: next.sampleRate, frameMs: next.frameMs, complexity: next.complexity }),
        }));
        serverContext.services.metrics.increment('commandsSent');
        sendJson(response, 200, { ok: true, commandId, channel: 'websocket', applied: next });
        return;
    }

    // Firebase fallback — reuse url field as JSON string payload
    const wrote = await serverContext.services.firebase.queueFallbackCommand(
        streamId, commandId, 'set_quality', JSON.stringify(next),
    );
    if (wrote) {
        serverContext.services.metrics.increment('commandsSent');
        sendJson(response, 202, { ok: true, commandId, channel: 'firebase', applied: next,
            note: 'phone offline — quality command queued in Firebase RTDB' });
        return;
    }
    sendJson(response, 409, { error: 'phone offline',
        hint: 'set FIREBASE_DB_SECRET in server/.env to enable queued commands' });
}

export function createRelayModule(): ServerModule {
    let heartbeatTimer: NodeJS.Timeout | null = null;
    let staleSocketTimer: NodeJS.Timeout | null = null;
    let statsBroadcastTimer: NodeJS.Timeout | null = null;
    let rateLimitPurgeTimer: NodeJS.Timeout | null = null;

    return {
        name: 'relay',
        register(serverContext) {
            const logger = serverContext.loggerFactory.forModule('Relay');

            return {
                httpRoutes: [
                    {
                        method: 'GET',
                        path: '/streams',
                        description: 'List active channels',
                        handler(_request, response) {
                            sendJson(response, 200, serverContext.services.channels.toSummaryList());
                        },
                    },
                    {
                        method: 'POST',
                        path: '/stream-control',
                        description: 'Send a remote stream control command',
                        handler: handleStreamControlRequest,
                    },
                    {
                        method: 'POST',
                        path: '/stream-quality',
                        description: 'Push audio quality config to Android app',
                        handler: handleSetQualityRequest,
                    },
                    {
                        method: 'POST',
                        path: '/volume',
                        description: 'Set server volume gain (0–5) and broadcast to all browser listeners',
                        handler: handleSetVolumeRequest,
                    },
                    {
                        method: 'POST',
                        path: '/stream-bitrate',
                        description: 'Hot-swap Opus encoding bitrate (16/32/64/128 kbps) — no mic restart',
                        handler: handleSetBitrateRequest,
                    },
                ],
                wsRoutes: [
                    {
                        path: '/stream',
                        description: 'Android microphone audio uplink',
                        handler: handleStreamSocket,
                    },
                    {
                        path: '/control',
                        description: 'Android remote control socket',
                        handler: handleControlSocket,
                    },
                    {
                        path: '/listen',
                        description: 'Browser listener socket',
                        handler: handleListenerSocket,
                    },
                ],
                healthChecks: [
                    {
                        name: 'channel-registry',
                        critical: true,
                        check(contextForCheck) {
                            return {
                                ok: true,
                                details: {
                                    activeChannels: contextForCheck.services.channels.size,
                                },
                            };
                        },
                    },
                ],
                onStart(runtimeContext: ServerRuntimeContext) {
                    heartbeatTimer = setInterval(() => {
                        runtimeContext.webSocketServer.clients.forEach(socket => {
                            const liveSocket = socket as LiveWebSocket;
                            if (liveSocket.isAlive === false) {
                                socket.terminate();
                                return;
                            }
                            liveSocket.isAlive = false;
                            socket.ping();
                        });
                    }, runtimeContext.config.wsPingIntervalMs);

                    staleSocketTimer = setInterval(() => {
                        for (const channel of runtimeContext.services.channels.values()) {
                            if (!isOpen(channel.phoneWs) || !channel.stats.lastActivityMs) {
                                continue;
                            }
                            if (Date.now() - channel.stats.lastActivityMs > runtimeContext.config.staleThresholdMs) {
                                logger.warn('Stale phone stream socket detected; terminating', {
                                    streamId: channel.id.slice(0, 8),
                                });
                                channel.phoneWs.terminate();
                            }
                        }
                    }, runtimeContext.config.staleCheckMs);

                    statsBroadcastTimer = setInterval(() => {
                        for (const channel of runtimeContext.services.channels.values()) {
                            if (channel.browsers.size === 0) {
                                continue;
                            }
                            runtimeContext.services.channels.broadcast(channel, JSON.stringify({
                                type: 'stats',
                                phoneConnected: runtimeContext.services.channels.isPhoneConnected(channel),
                                streamingActive: channel.streamingActive,
                                framesRelayed: channel.stats.framesRelayed,
                                kbRelayed: (channel.stats.bytesRelayed / 1024).toFixed(1),
                                uptimeSec: channel.stats.connectedAt
                                    ? Math.floor((Date.now() - channel.stats.connectedAt) / 1000)
                                    : 0,
                                volumeGain: runtimeContext.config.serverVolumeGain,
                                audioMode: channel.audioMode,
                                qualityConfig: channel.qualityConfig,
                            }));
                        }
                    }, runtimeContext.config.statsIntervalMs);

                    rateLimitPurgeTimer = setInterval(() => {
                        runtimeContext.services.rateLimit.purgeStale();
                    }, runtimeContext.config.rateWindowMs);
                },
                onStop() {
                    if (heartbeatTimer) {
                        clearInterval(heartbeatTimer);
                    }
                    if (staleSocketTimer) {
                        clearInterval(staleSocketTimer);
                    }
                    if (statsBroadcastTimer) {
                        clearInterval(statsBroadcastTimer);
                    }
                    if (rateLimitPurgeTimer) {
                        clearInterval(rateLimitPurgeTimer);
                    }
                    heartbeatTimer = null;
                    staleSocketTimer = null;
                    statsBroadcastTimer = null;
                    rateLimitPurgeTimer = null;
                },
            };
        },
    };
}
