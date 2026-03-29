'use strict';

/**
 * Audio Cast contract tests
 * Run with: npm test  (requires compiled server in dist/)
 */

const { ok, strictEqual } = require('node:assert/strict');
const { after, before, describe, it } = require('node:test');

// ── Minimal test harness that boots the server in-process ─────────────────────
const path = require('node:path');
const { createAuraCastServer } = require('../dist/server/create-server');

let server;
let baseUrl;

// Shared across tests so upload→play works in sequence
let uploadedAudioId = null;

async function startServer() {
    if (server) return;
    const instance = createAuraCastServer({
        projectRoot: path.resolve(__dirname, '..'),
        config: {
            nodeEnv: 'test',
            port: 0,
            logLevel: 'ERROR',
            logFormat: 'pretty',
            firebaseDbUrl: 'https://example.firebaseio.test',
            firebaseSecret: '',
            firebaseServiceAccountPath: null,
            firebaseServiceAccountJson: null,
            audioCastStoreDir: '.local/audio-test',
        },
    });
    const port = await instance.start();
    baseUrl = `http://127.0.0.1:${port}`;
    server = instance;
}

async function stopServer() {
    if (server) { await server.stop(); server = null; }
}

// ── Helper: build multipart body ──────────────────────────────────────────────
function buildMultipart(boundary, fieldName, fileName, mimeType, data) {
    const CRLF = '\r\n';
    const head = [
        `--${boundary}`,
        `Content-Disposition: form-data; name="${fieldName}"; filename="${fileName}"`,
        `Content-Type: ${mimeType}`,
        '',
        '',
    ].join(CRLF);
    const tail = `${CRLF}--${boundary}--${CRLF}`;
    return Buffer.concat([Buffer.from(head), data, Buffer.from(tail)]);
}

async function httpPost(url, body, headers = {}) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(url);
        const req = require('node:http').request({
            hostname: parsed.hostname, port: parsed.port,
            path: parsed.pathname + parsed.search,
            method: 'POST', headers,
        }, resp => {
            const chunks = [];
            resp.on('data', c => chunks.push(c));
            resp.on('end', () => resolve({ status: resp.statusCode,
                body: Buffer.concat(chunks).toString(),
                json() { return JSON.parse(this.body); } }));
        });
        req.on('error', reject);
        if (body) req.write(body);
        req.end();
    });
}

async function httpGet(url) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(url);
        require('node:http').get({
            hostname: parsed.hostname, port: parsed.port,
            path: parsed.pathname + parsed.search,
        }, resp => {
            const chunks = [];
            resp.on('data', c => chunks.push(c));
            resp.on('end', () => resolve({ status: resp.statusCode,
                body: Buffer.concat(chunks),
                headers: resp.headers }));
        }).on('error', reject);
    });
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('audio-cast contracts', { concurrency: false }, () => {
    before(async () => {
        await startServer();
    });

    after(async () => {
        await stopServer();
    });

    it('POST /audio-upload with valid MP3 returns 200 + audioId', async () => {
        const boundary = 'testboundary123';
        // Minimal valid MP3 header bytes (ID3v2 tag)
        const fakeAudio = Buffer.from([0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
        const body = buildMultipart(boundary, 'audio', 'test.mp3', 'audio/mpeg', fakeAudio);
        const resp = await httpPost(`${baseUrl}/audio-upload`, body, {
            'Content-Type': `multipart/form-data; boundary=${boundary}`,
            'Content-Length': body.length,
        });
        strictEqual(resp.status, 200);
        const j = resp.json();
        ok(j.ok, 'expected ok:true');
        ok(j.audioId, 'expected audioId');
        ok(j.downloadUrl.startsWith('/audio/'), 'expected downloadUrl');
        strictEqual(j.name, 'test.mp3');
        uploadedAudioId = j.audioId;
    });

    it('POST /audio-upload oversized file returns 413', async () => {
        const boundary = 'testboundary456';
        const fakeAudio = Buffer.alloc(10);
        const body = buildMultipart(boundary, 'audio', 'big.mp3', 'audio/mpeg', fakeAudio);
        const resp = await httpPost(`${baseUrl}/audio-upload`, body, {
            'Content-Type': `multipart/form-data; boundary=${boundary}`,
            'Content-Length': (51 * 1024 * 1024).toString(),
        });
        strictEqual(resp.status, 413);
    });

    it('POST /audio-upload wrong MIME type returns 415', async () => {
        const boundary = 'testboundary789';
        const fakeData = Buffer.from('not audio');
        const body = buildMultipart(boundary, 'audio', 'evil.exe', 'application/x-msdownload', fakeData);
        const resp = await httpPost(`${baseUrl}/audio-upload`, body, {
            'Content-Type': `multipart/form-data; boundary=${boundary}`,
            'Content-Length': body.length,
        });
        strictEqual(resp.status, 415);
    });

    it('GET /audio/<id> for uploaded file returns 200 binary', async () => {
        ok(uploadedAudioId, 'need an uploaded file from previous test');
        const resp = await httpGet(`${baseUrl}/audio/${uploadedAudioId}`);
        strictEqual(resp.status, 200);
        ok(resp.headers['content-type'].startsWith('audio/'), 'expected audio content-type');
        ok(resp.body.length > 0, 'expected non-empty body');
    });

    it('GET /audio/<unknown-id> returns 404', async () => {
        const resp = await httpGet(`${baseUrl}/audio/00000000-0000-0000-0000-000000000000`);
        strictEqual(resp.status, 404);
    });

    it('POST /audio-play with missing stream returns 404', async () => {
        ok(uploadedAudioId, 'need an uploaded audioId');
        const resp = await httpPost(
            `${baseUrl}/audio-play?id=nonexistent-stream`,
            JSON.stringify({ audioId: uploadedAudioId }),
            { 'Content-Type': 'application/json' }
        );
        strictEqual(resp.status, 404);
    });

    it('POST /audio-stop with missing stream returns 404', async () => {
        const resp = await httpPost(
            `${baseUrl}/audio-stop?id=nonexistent-stream`,
            '{}',
            { 'Content-Type': 'application/json' }
        );
        strictEqual(resp.status, 404);
    });

    it('GET /audio-cast serves HTML page', async () => {
        const resp = await httpGet(`${baseUrl}/audio-cast`);
        strictEqual(resp.status, 200);
        ok(resp.body.toString().includes('Audio Cast'), 'expected Audio Cast page');
    });
});
