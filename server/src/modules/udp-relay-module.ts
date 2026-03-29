import * as dgram from 'node:dgram';
import WebSocket from 'ws';
import type { ServerModule, ServerRuntimeContext } from '../core/contracts';
import type { UdpRelayService } from '../services/types';

const HEADER_SIZE = 12;
const SESSION_EXPIRY_MS = 30 * 60 * 1_000;   // 30 minutes idle
const PURGE_INTERVAL_MS =  5 * 60 * 1_000;   // purge check every 5 minutes

interface SessionEntry {
    channelId: string;
    lastSeen: number;
}

/** `ServerModule` extended with the public service interface consumed by `relay-module`. */
export interface UdpRelayModule extends ServerModule {
    readonly service: UdpRelayService;
}

export function createUdpRelayModule(): UdpRelayModule {
    const sessions = new Map<string, SessionEntry>();

    // Service object handed to ServerContext.services at construction time,
    // before onStart() is called — relay-module.ts registers sessions via this ref.
    const service: UdpRelayService = {
        registerSession(token: string, channelId: string): void {
            // Key = first 16 hex chars (8 bytes) — matches the token8 field in UDP header
            const token8 = token.substring(0, 16);
            sessions.set(token8, { channelId, lastSeen: Date.now() });
        },
    };

    return {
        name: 'udp-relay',
        service,

        register() {
            let purgeTimer: NodeJS.Timeout | null = null;

            return {
                healthChecks: [
                    {
                        name: 'udp-relay',
                        critical: false,
                        check() {
                            return { ok: true, details: { activeSessions: sessions.size } };
                        },
                    },
                ],

                onStart(runtimeContext: ServerRuntimeContext) {
                    const logger = runtimeContext.loggerFactory.forModule('UdpRelay');
                    const socket = dgram.createSocket('udp4');

                    socket.on('error', (error) => {
                        logger.error('UDP socket error', { error: error.message });
                    });

                    socket.on('message', (msg: Buffer) => {
                        // Minimum sanity: header size + version byte
                        if (msg.length < HEADER_SIZE) return;
                        if (msg[0] !== 0x01) return;   // unsupported version — drop

                        // Bytes 4–11 are the 8-byte session token
                        const token8 = msg.subarray(4, 12).toString('hex');
                        const session = sessions.get(token8);
                        if (!session) return;           // unknown session — drop silently

                        session.lastSeen = Date.now();

                        const payload = msg.subarray(HEADER_SIZE);
                        const channel = runtimeContext.services.channels.getChannel(session.channelId);
                        if (!channel) return;

                        channel.stats.framesRelayed += 1;
                        channel.stats.bytesRelayed += payload.length;
                        runtimeContext.services.metrics.increment('audioFrames');

                        for (const browser of channel.browsers) {
                            if (browser.readyState === WebSocket.OPEN) {
                                browser.send(payload, { binary: true });
                            }
                        }
                    });

                    socket.on('listening', () => {
                        const addr = socket.address();
                        logger.info('UDP relay listening', { port: addr.port });
                    });

                    socket.bind(runtimeContext.config.udpPort);

                    // Expose socket on the runtime context for introspection / graceful stop
                    runtimeContext.udpServer = socket;

                    // Purge idle sessions
                    purgeTimer = setInterval(() => {
                        const cutoff = Date.now() - SESSION_EXPIRY_MS;
                        for (const [k, v] of sessions) {
                            if (v.lastSeen < cutoff) sessions.delete(k);
                        }
                    }, PURGE_INTERVAL_MS);
                },

                onStop(runtimeContext: ServerRuntimeContext) {
                    if (purgeTimer) {
                        clearInterval(purgeTimer);
                        purgeTimer = null;
                    }
                    if (runtimeContext.udpServer) {
                        runtimeContext.udpServer.close();
                        runtimeContext.udpServer = undefined;
                    }
                },
            };
        },
    };
}
