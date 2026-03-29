const { after, before, describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');
const WebSocket = require('ws');
const { createAuraCastServer } = require('../dist/server/create-server.js');

class FakeFirebaseService {
  constructor() {
    this.adminReady = true;
    this.queuedCommands = [];
    this.wakeCommands = [];
  }

  getHealthSnapshot() {
    return {
      adminConfigured: true,
      adminReady: this.adminReady,
      restFallbackConfigured: true,
      serviceAccountPath: null,
    };
  }

  async queueFallbackCommand(streamId, commandId, action, url = '') {
    this.queuedCommands.push({ streamId, commandId, action, url });
    return true;
  }

  async sendWake(streamId) {
    this.wakeCommands.push(streamId);
    return { commandId: `wake-${streamId}` };
  }

  async dispose() {}
}

describe('AuraCast server contracts', () => {
  const firebase = new FakeFirebaseService();
  const server = createAuraCastServer({
    config: {
      nodeEnv: 'test',
      port: 0,
      udpPort: 0,
      firebaseDbUrl: 'https://example.firebaseio.test',
      firebaseSecret: 'secret',
      firebaseServiceAccountPath: null,
      firebaseServiceAccountJson: null,
      wsPingIntervalMs: 1000,
      staleThresholdMs: 60000,
      staleCheckMs: 1000,
      statsIntervalMs: 1000,
      channelGraceMs: 5000,
      serverVolumeGain: 3,
      logLevel: 'ERROR',
      logFormat: 'pretty',
      rateLimitEnabled: false,
      rateWindowMs: 1000,
      maxRequestsPerWindow: 100,
      shutdownTimeoutMs: 7000,
      audioCastMaxSizeMb: 50,
      audioCastTtlHours: 2,
      audioCastStoreDir: '.local/audio-test-contract',
    },
    serviceOverrides: {
      firebase,
    },
  });

  let port = 0;

  before(async () => {
    port = await server.start();
  });

  after(async () => {
    await server.stop();
  });

  it('serves the dashboard and player pages', async () => {
    const dashboardResponse = await fetch(`http://127.0.0.1:${port}/`);
    const dashboardHtml = await dashboardResponse.text();
    assert.equal(dashboardResponse.status, 200);
    assert.match(dashboardHtml, /AuraCast/);

    const playerResponse = await fetch(`http://127.0.0.1:${port}/player?id=test-stream`);
    const playerHtml = await playerResponse.text();
    assert.equal(playerResponse.status, 200);
    assert.match(playerHtml, /Remote Control/);
  });

  it('keeps the /streams and /health contracts', async () => {
    const streamsResponse = await fetch(`http://127.0.0.1:${port}/streams`);
    assert.equal(streamsResponse.status, 200);
    assert.deepEqual(await streamsResponse.json(), []);

    const healthResponse = await fetch(`http://127.0.0.1:${port}/health`);
    const health = await healthResponse.json();
    assert.equal(healthResponse.status, 200);
    assert.equal(health.status, 'ok');
    assert.equal(typeof health.totalChannels, 'number');
    assert.equal(typeof health.totalFrames, 'number');
  });

  it('routes audio frames and websocket control messages without changing endpoints', async () => {
    const streamId = '11111111-1111-1111-1111-111111111111';
    const controlSocket = new WebSocket(`ws://127.0.0.1:${port}/control?id=${streamId}&name=Pixel`);
    const welcomePromise = once(controlSocket, 'message');
    await once(controlSocket, 'open');
    const [welcomePayload] = await welcomePromise;
    assert.match(welcomePayload.toString(), /welcome/);

    const streamSocket = new WebSocket(`ws://127.0.0.1:${port}/stream?id=${streamId}&name=Pixel`);
    await once(streamSocket, 'open');

    const listenerSocket = new WebSocket(`ws://127.0.0.1:${port}/listen?id=${streamId}`);
    const initialStatusPromise = once(listenerSocket, 'message');
    await once(listenerSocket, 'open');
    const [initialStatus] = await initialStatusPromise;
    assert.match(initialStatus.toString(), /"status"/);

    const audioFrame = Buffer.from([1, 2, 3, 4]);
    streamSocket.send(audioFrame);
    const [relayedFrame, isBinary] = await once(listenerSocket, 'message');
    assert.equal(Boolean(isBinary), true);
    assert.deepEqual(Buffer.from(relayedFrame), audioFrame);

    const commandPromise = once(controlSocket, 'message');
    const response = await fetch(`http://127.0.0.1:${port}/stream-control?id=${streamId}&action=stop`, {
      method: 'POST',
    });
    const payload = await response.json();
    assert.equal(response.status, 200);
    assert.equal(payload.channel, 'websocket');

    const [commandPayload] = await commandPromise;
    assert.match(commandPayload.toString(), /"type":"cmd"/);

    listenerSocket.close();
    streamSocket.close();
    controlSocket.close();
  });

  it('falls back to Firebase for queued commands and wake calls', async () => {
    const streamId = '22222222-2222-2222-2222-222222222222';
    const listenerSocket = new WebSocket(`ws://127.0.0.1:${port}/listen?id=${streamId}`);
    const initialStatusPromise = once(listenerSocket, 'message');
    await once(listenerSocket, 'open');
    await initialStatusPromise;

    const fallbackResponse = await fetch(`http://127.0.0.1:${port}/stream-control?id=${streamId}&action=start`, {
      method: 'POST',
    });
    const fallbackPayload = await fallbackResponse.json();
    assert.equal(fallbackResponse.status, 202);
    assert.equal(fallbackPayload.channel, 'firebase');
    assert.equal(firebase.queuedCommands.at(-1)?.streamId, streamId);

    const wakeResponse = await fetch(`http://127.0.0.1:${port}/wake?id=${streamId}`, {
      method: 'POST',
    });
    const wakePayload = await wakeResponse.json();
    assert.equal(wakeResponse.status, 200);
    assert.equal(wakePayload.channel, 'firebase-admin');
    assert.equal(firebase.wakeCommands.at(-1), streamId);

    const readyResponse = await fetch(`http://127.0.0.1:${port}/ready`);
    const readyPayload = await readyResponse.json();
    assert.equal(readyResponse.status, 200);
    assert.equal(readyPayload.status, 'ready');

    listenerSocket.close();
  });
});
