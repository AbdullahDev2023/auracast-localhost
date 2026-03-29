import { createReadStream, statSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { sendJson, sendFile } from '../core/http';
import type {
    RequestContext,
    ServerContext,
    ServerModule,
    ServerRuntimeContext,
} from '../core/contracts';

// ── Content-Disposition helpers ──────────────────────────────────────────────

/**
 * Build a safe Content-Disposition header value for an arbitrary filename.
 *
 * Node.js's http module rejects header values that contain characters outside
 * the ISO-8859-1 printable range or control characters (throws ERR_INVALID_CHAR),
 * which causes a 500 before any bytes are sent to the client.
 *
 * Strategy (RFC 6266 / RFC 5987):
 *  - `filename=` uses a sanitised ASCII fallback (non-ASCII → '_', quotes/backslashes stripped).
 *  - `filename*=` carries the full UTF-8 name percent-encoded so modern clients show the real name.
 */
function buildContentDisposition(originalName: string): string {
    // ASCII fallback: keep printable ASCII except " and \
    const ascii = originalName
        .replace(/[^\x20-\x7E]/g, '_')   // replace non-printable / non-ASCII
        .replace(/["\\\r\n]/g, '_');      // strip chars illegal inside a quoted-string
    // RFC 5987 extended value — percent-encode the full UTF-8 name
    const encoded = encodeURIComponent(originalName);
    return `attachment; filename="${ascii}"; filename*=UTF-8''${encoded}`;
}

// ── URL resolution ────────────────────────────────────────────────────────────

/**
 * Build the absolute URL the Android device will use to download an audio file.
 *
 * Priority:
 *   1. `PUBLIC_HOST` env var — set this to your ngrok/public URL, e.g.
 *      "https://abc123.ngrok-free.app" or "abc123.ngrok-free.app"
 *      The Android device may be anywhere in the world and can never reach
 *      `localhost`; always set PUBLIC_HOST when using ngrok or any remote device.
 *   2. `x-forwarded-proto` + `host` headers — works when the operator browser
 *      also connects through the same public proxy (e.g. the dashboard is opened
 *      via the ngrok URL, not localhost).
 *   3. Plain `host` header with http — last-resort for same-LAN use only.
 */
function resolveAudioUrl(audioId: string, request: IncomingMessage, serverContext: ServerContext): string {
    const publicHost = serverContext.config.publicHost?.trim();
    if (publicHost) {
        const base = /^https?:\/\//i.test(publicHost)
            ? publicHost.replace(/\/$/, '')
            : `https://${publicHost}`;
        return `${base}/audio/${audioId}`;
    }
    // Fallback: derive from incoming request headers
    const host  = request.headers['host'] || 'localhost';
    const proto = (request.headers['x-forwarded-proto'] as string) || 'http';
    return `${proto}://${host}/audio/${audioId}`;
}

// ── Multipart helpers ─────────────────────────────────────────────────────────

function extractBoundary(contentType: string): string | null {
    const m = contentType.match(/boundary=([^\s;]+)/i);
    return m?.[1]?.replace(/^"(.*)"$/, '$1') ?? null;
}

interface MultipartFile {
    fieldName: string;
    fileName: string;
    mimeType: string;
    data: Buffer;
}

async function readBody(request: IncomingMessage): Promise<Buffer> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = [];
        request.on('data', (chunk: Buffer) => chunks.push(chunk));
        request.on('end', () => resolve(Buffer.concat(chunks)));
        request.on('error', reject);
    });
}

function parseMultipart(body: Buffer, boundary: string): MultipartFile | null {
    const sep = Buffer.from(`--${boundary}`);
    const crlf = Buffer.from('\r\n');
    const parts: Buffer[] = [];
    let offset = 0;
    while (offset < body.length) {
        const idx = body.indexOf(sep, offset);
        if (idx < 0) break;
        const start = idx + sep.length;
        if (body[start] === 0x2d && body[start + 1] === 0x2d) break; // --boundary--
        const end = body.indexOf(sep, start);
        if (end < 0) break;
        // part = CRLF + headers + CRLF CRLF + data + CRLF
        const partRaw = body.slice(start + crlf.length, end - crlf.length);
        parts.push(partRaw);
        offset = end;
    }
    for (const part of parts) {
        const headerEnd = part.indexOf(Buffer.from('\r\n\r\n'));
        if (headerEnd < 0) continue;
        const headerText = part.slice(0, headerEnd).toString('utf-8');
        const data = part.slice(headerEnd + 4);
        const dispMatch = headerText.match(/Content-Disposition:[^\r\n]*?name="([^"]*)"(?:[^\r\n]*?filename="([^"]*)")?/i);
        const typeMatch  = headerText.match(/Content-Type:\s*([^\r\n]+)/i);
        const fieldName = dispMatch?.[1];
        const fileName = dispMatch?.[2];
        if (!fieldName || !fileName) continue; // skip non-file fields
        return {
            fieldName,
            fileName,
            mimeType:  typeMatch?.[1]?.trim() ?? 'application/octet-stream',
            data,
        };
    }
    return null;
}

// ── Route handlers ────────────────────────────────────────────────────────────

async function handleUpload(
    request: IncomingMessage,
    response: ServerResponse,
    context: RequestContext,
    serverContext: ServerContext,
): Promise<void> {
    const ct = request.headers['content-type'] || '';
    const boundary = extractBoundary(ct);
    if (!boundary) { sendJson(response, 400, { error: 'Expected multipart/form-data' }); return; }

    const maxBytes = serverContext.config.audioCastMaxSizeMb * 1024 * 1024;
    const rawContentLength = parseInt(request.headers['content-length'] || '0', 10);
    if (rawContentLength > maxBytes) {
        response.setHeader('Connection', 'close');
        request.resume();
        sendJson(response, 413, { error: 'File too large' });
        return;
    }

    const body = await readBody(request);
    if (body.length > maxBytes) { sendJson(response, 413, { error: 'File too large' }); return; }

    const file = parseMultipart(body, boundary);
    if (!file) { sendJson(response, 400, { error: 'No audio file found in request' }); return; }

    if (!file.mimeType.startsWith('audio/') && !file.mimeType.startsWith('application/octet-stream')) {
        sendJson(response, 415, { error: 'Only audio/* MIME types are accepted' });
        return;
    }

    const entry = serverContext.services.audioStore.store(file.data, file.fileName, file.mimeType);
    context.requestLogger.info('Audio uploaded', { audioId: entry.id, name: entry.originalName, bytes: file.data.length });
    sendJson(response, 200, { ok: true, audioId: entry.id, downloadUrl: `/audio/${entry.id}`, name: entry.originalName });
}

function handleServeAudio(
    request: IncomingMessage,
    response: ServerResponse,
    context: RequestContext,
    serverContext: ServerContext,
): void {
    const audioId = context.url.pathname.split('/').pop() || '';
    const entry = serverContext.services.audioStore.get(audioId);
    if (!entry) { sendJson(response, 404, { error: 'audio not found' }); return; }

    let size = 0;
    try {
        size = statSync(entry.storedPath).size;
    } catch {
        sendJson(response, 404, { error: 'audio not found' });
        return;
    }

    try {
        response.writeHead(200, {
            'Content-Type': entry.mimeType,
            'Content-Length': size,
            'Content-Disposition': buildContentDisposition(entry.originalName),
            'Cache-Control': 'no-cache',
            'Accept-Ranges': 'bytes',
        });
    } catch (headerErr) {
        context.requestLogger.error('Failed to write audio response headers', {
            error: headerErr instanceof Error ? headerErr.message : String(headerErr),
            audioId,
            originalName: entry.originalName,
        });
        if (!response.headersSent) {
            sendJson(response, 500, { error: 'failed to build response headers' });
        } else if (!response.writableEnded) {
            response.end();
        }
        return;
    }

    createReadStream(entry.storedPath)
        .on('error', (streamErr) => {
            context.requestLogger.error('Audio stream error', {
                error: streamErr.message,
                audioId,
            });
            if (!response.writableEnded) response.end();
        })
        .pipe(response);
}

async function handleAudioPlay(
    request: IncomingMessage,
    response: ServerResponse,
    context: RequestContext,
    serverContext: ServerContext,
): Promise<void> {
    const streamId = context.url.searchParams.get('id');
    const channel = streamId ? serverContext.services.channels.getChannel(streamId) : null;
    if (!channel || !streamId) { sendJson(response, 404, { error: 'channel not found' }); return; }

    const chunks: Buffer[] = [];
    await new Promise<void>((res, rej) => {
        request.on('data', (c: Buffer) => chunks.push(c));
        request.on('end', res);
        request.on('error', rej);
    });
    const body = JSON.parse(Buffer.concat(chunks).toString() || '{}') as { audioId?: string };
    const entry = body.audioId ? serverContext.services.audioStore.get(body.audioId) : undefined;
    if (!entry) { sendJson(response, 404, { error: 'audio not found — upload first' }); return; }

    // Build the absolute download URL the Android device will fetch.
    const downloadUrl = resolveAudioUrl(entry.id, request, serverContext);

    const commandId = randomUUID();
    const controlWs = channel.controlWs;
    if (controlWs && controlWs.readyState === 1 /* OPEN */) {
        controlWs.send(JSON.stringify({ type: 'cmd', commandId, action: 'play_audio', url: downloadUrl, source: 'websocket' }));
        serverContext.services.metrics.increment('commandsSent');
        sendJson(response, 200, { ok: true, commandId, channel: 'websocket', action: 'play_audio', downloadUrl });
        return;
    }
    const wrote = await serverContext.services.firebase.queueFallbackCommand(streamId, commandId, 'play_audio', downloadUrl);
    if (wrote) {
        serverContext.services.metrics.increment('commandsSent');
        sendJson(response, 202, { ok: true, commandId, channel: 'firebase', action: 'play_audio', note: 'phone offline — queued' });
        return;
    }
    sendJson(response, 409, { error: 'phone offline', hint: 'set FIREBASE_DB_SECRET to queue commands' });
}

// ── Generic command helper ────────────────────────────────────────────────────

async function sendSimpleCommand(
    request: IncomingMessage,
    response: ServerResponse,
    context: RequestContext,
    serverContext: ServerContext,
    action: string,
    urlPayload: string = '',
): Promise<void> {
    const streamId = context.url.searchParams.get('id');
    const channel  = streamId ? serverContext.services.channels.getChannel(streamId) : null;
    if (!channel || !streamId) { sendJson(response, 404, { error: 'channel not found' }); return; }
    const commandId  = randomUUID();
    const controlWs  = channel.controlWs;
    if (controlWs && controlWs.readyState === 1 /* OPEN */) {
        controlWs.send(JSON.stringify({ type: 'cmd', commandId, action, url: urlPayload, source: 'websocket' }));
        serverContext.services.metrics.increment('commandsSent');
        sendJson(response, 200, { ok: true, commandId, channel: 'websocket', action });
        return;
    }
    const wrote = await serverContext.services.firebase.queueFallbackCommand(streamId, commandId, action, urlPayload);
    if (wrote) {
        serverContext.services.metrics.increment('commandsSent');
        sendJson(response, 202, { ok: true, commandId, channel: 'firebase', action, note: 'phone offline — queued' });
        return;
    }
    sendJson(response, 409, { error: 'phone offline', hint: 'set FIREBASE_DB_SECRET to queue commands' });
}

// Pause
async function handleAudioPause(req: IncomingMessage, res: ServerResponse, ctx: RequestContext, sc: ServerContext) {
    return sendSimpleCommand(req, res, ctx, sc, 'pause_audio');
}
// Resume
async function handleAudioResume(req: IncomingMessage, res: ServerResponse, ctx: RequestContext, sc: ServerContext) {
    return sendSimpleCommand(req, res, ctx, sc, 'resume_audio');
}
// Loop (finite) — body: { audioId, count }
async function handleAudioLoop(
    request: IncomingMessage, response: ServerResponse, context: RequestContext, serverContext: ServerContext,
): Promise<void> {
    const streamId = context.url.searchParams.get('id');
    const channel  = streamId ? serverContext.services.channels.getChannel(streamId) : null;
    if (!channel || !streamId) { sendJson(response, 404, { error: 'channel not found' }); return; }
    const chunks: Buffer[] = [];
    await new Promise<void>((res, rej) => { request.on('data', (c: Buffer) => chunks.push(c)); request.on('end', res); request.on('error', rej); });
    const body  = JSON.parse(Buffer.concat(chunks).toString() || '{}') as { audioId?: string; count?: number };
    const entry = body.audioId ? serverContext.services.audioStore.get(body.audioId) : undefined;
    if (!entry) { sendJson(response, 404, { error: 'audio not found — upload first' }); return; }
    const downloadUrl = resolveAudioUrl(entry.id, request, serverContext);
    const count = Math.max(1, body.count ?? 1);
    const payload = JSON.stringify({ url: downloadUrl, count });
    return sendSimpleCommand(request, response, context, serverContext, 'loop_audio', payload);
}
// Loop infinite — body: { audioId }
async function handleAudioLoopInfinite(
    request: IncomingMessage, response: ServerResponse, context: RequestContext, serverContext: ServerContext,
): Promise<void> {
    const streamId = context.url.searchParams.get('id');
    const channel  = streamId ? serverContext.services.channels.getChannel(streamId) : null;
    if (!channel || !streamId) { sendJson(response, 404, { error: 'channel not found' }); return; }
    const chunks: Buffer[] = [];
    await new Promise<void>((res, rej) => { request.on('data', (c: Buffer) => chunks.push(c)); request.on('end', res); request.on('error', rej); });
    const body  = JSON.parse(Buffer.concat(chunks).toString() || '{}') as { audioId?: string };
    const entry = body.audioId ? serverContext.services.audioStore.get(body.audioId) : undefined;
    if (!entry) { sendJson(response, 404, { error: 'audio not found — upload first' }); return; }
    const downloadUrl = resolveAudioUrl(entry.id, request, serverContext);
    return sendSimpleCommand(request, response, context, serverContext, 'loop_infinite', downloadUrl);
}
// Queue add — body: { audioId }
async function handleQueueAdd(
    request: IncomingMessage, response: ServerResponse, context: RequestContext, serverContext: ServerContext,
): Promise<void> {
    const streamId = context.url.searchParams.get('id');
    const channel  = streamId ? serverContext.services.channels.getChannel(streamId) : null;
    if (!channel || !streamId) { sendJson(response, 404, { error: 'channel not found' }); return; }
    const chunks: Buffer[] = [];
    await new Promise<void>((res, rej) => { request.on('data', (c: Buffer) => chunks.push(c)); request.on('end', res); request.on('error', rej); });
    const body  = JSON.parse(Buffer.concat(chunks).toString() || '{}') as { audioId?: string };
    const entry = body.audioId ? serverContext.services.audioStore.get(body.audioId) : undefined;
    if (!entry) { sendJson(response, 404, { error: 'audio not found — upload first' }); return; }
    const downloadUrl = resolveAudioUrl(entry.id, request, serverContext);
    return sendSimpleCommand(request, response, context, serverContext, 'queue_add', downloadUrl);
}
// Queue clear
async function handleQueueClear(req: IncomingMessage, res: ServerResponse, ctx: RequestContext, sc: ServerContext) {
    return sendSimpleCommand(req, res, ctx, sc, 'queue_clear');
}
// Set volume — body: { volume: 0.0..1.0 }
async function handleSetVolume(
    request: IncomingMessage, response: ServerResponse, context: RequestContext, serverContext: ServerContext,
): Promise<void> {
    const chunks: Buffer[] = [];
    await new Promise<void>((res, rej) => { request.on('data', (c: Buffer) => chunks.push(c)); request.on('end', res); request.on('error', rej); });
    const body   = JSON.parse(Buffer.concat(chunks).toString() || '{}') as { volume?: number };
    const volume = Math.min(1, Math.max(0, body.volume ?? 1));
    return sendSimpleCommand(request, response, context, serverContext, 'set_volume', String(volume));
}

// Seek — body: { positionMs: number }
async function handleAudioSeek(
    request: IncomingMessage, response: ServerResponse, context: RequestContext, serverContext: ServerContext,
): Promise<void> {
    const chunks: Buffer[] = [];
    await new Promise<void>((res, rej) => { request.on('data', (c: Buffer) => chunks.push(c)); request.on('end', res); request.on('error', rej); });
    const body        = JSON.parse(Buffer.concat(chunks).toString() || '{}') as { positionMs?: number };
    const positionMs  = Math.max(0, Math.round(body.positionMs ?? 0));
    return sendSimpleCommand(request, response, context, serverContext, 'seek_audio', String(positionMs));
}

async function handleAudioStop(
    request: IncomingMessage,
    response: ServerResponse,
    context: RequestContext,
    serverContext: ServerContext,
): Promise<void> {
    const streamId = context.url.searchParams.get('id');
    const channel = streamId ? serverContext.services.channels.getChannel(streamId) : null;
    if (!channel || !streamId) { sendJson(response, 404, { error: 'channel not found' }); return; }
    const commandId = randomUUID();
    const controlWs = channel.controlWs;
    if (controlWs && controlWs.readyState === 1) {
        controlWs.send(JSON.stringify({ type: 'cmd', commandId, action: 'stop_audio', url: '', source: 'websocket' }));
        serverContext.services.metrics.increment('commandsSent');
        sendJson(response, 200, { ok: true, commandId, channel: 'websocket', action: 'stop_audio' });
        return;
    }
    const wrote = await serverContext.services.firebase.queueFallbackCommand(streamId, commandId, 'stop_audio', '');
    if (wrote) {
        serverContext.services.metrics.increment('commandsSent');
        sendJson(response, 202, { ok: true, commandId, channel: 'firebase', action: 'stop_audio', note: 'phone offline — queued' });
        return;
    }
    sendJson(response, 409, { error: 'phone offline', hint: 'set FIREBASE_DB_SECRET to queue commands' });
}

// ── Module export ─────────────────────────────────────────────────────────────

export function createAudioCastModule(): ServerModule {
    let ttlPurgeTimer: NodeJS.Timeout | null = null;

    return {
        name: 'audio-cast',
        register(serverContext: ServerContext) {
            return {
                httpRoutes: [
                    {
                        method: 'GET',
                        path: '/audio-cast',
                        description: 'Serve the Audio Cast operator page',
                        handler(_req, res) {
                            sendFile(res, serverContext.paths.publicDir, 'audio-cast.html');
                        },
                    },
                    {
                        method: 'POST',
                        path: '/audio-upload',
                        description: 'Upload an audio file to the server store',
                        handler: handleUpload,
                    },
                    {
                        method: 'GET',
                        path: '/audio/',
                        description: 'Serve a stored audio file by id',
                        handler: handleServeAudio,
                    },
                    {
                        method: 'GET',
                        path: '/audio',
                        description: 'Serve a stored audio file by id  (/audio?id= not used — path routing only)',
                        handler(_req, res) { sendJson(res, 400, { error: 'use /audio/<id>' }); },
                    },
                    {
                        method: 'POST',
                        path: '/audio-play',
                        description: 'Send play_audio command to Android device',
                        handler: handleAudioPlay,
                    },
                    {
                        method: 'POST',
                        path: '/audio-stop',
                        description: 'Send stop_audio command to Android device',
                        handler: handleAudioStop,
                    },
                    {
                        method: 'POST',
                        path: '/audio-pause',
                        description: 'Send pause_audio command to Android device',
                        handler: handleAudioPause,
                    },
                    {
                        method: 'POST',
                        path: '/audio-resume',
                        description: 'Send resume_audio command to Android device',
                        handler: handleAudioResume,
                    },
                    {
                        method: 'POST',
                        path: '/audio-loop',
                        description: 'Send loop_audio command — body: { audioId, count }',
                        handler: handleAudioLoop,
                    },
                    {
                        method: 'POST',
                        path: '/audio-loop-infinite',
                        description: 'Send loop_infinite command — body: { audioId }',
                        handler: handleAudioLoopInfinite,
                    },
                    {
                        method: 'POST',
                        path: '/audio-queue-add',
                        description: 'Append a track to the play queue — body: { audioId }',
                        handler: handleQueueAdd,
                    },
                    {
                        method: 'POST',
                        path: '/audio-queue-clear',
                        description: 'Clear the play queue on the Android device',
                        handler: handleQueueClear,
                    },
                    {
                        method: 'POST',
                        path: '/audio-volume',
                        description: 'Set playback volume — body: { volume: 0.0..1.0 }',
                        handler: handleSetVolume,
                    },
                    {
                        method: 'POST',
                        path: '/audio-seek',
                        description: 'Seek to position — body: { positionMs: number }',
                        handler: handleAudioSeek,
                    },
                ],
                healthChecks: [
                    {
                        name: 'audio-store',
                        critical: false,
                        check(ctx) {
                            return { ok: true, details: { storedFiles: ctx.services.audioStore.size } };
                        },
                    },
                ],
                onStart(runtimeContext: ServerRuntimeContext) {
                    const ttlMs = runtimeContext.config.audioCastTtlHours * 60 * 60 * 1000;
                    ttlPurgeTimer = setInterval(() => {
                        runtimeContext.services.audioStore.purgeExpired(ttlMs);
                    }, 15 * 60 * 1000); // check every 15 min
                },
                onStop() {
                    if (ttlPurgeTimer) { clearInterval(ttlPurgeTimer); ttlPurgeTimer = null; }
                },
            };
        },
    };
}
