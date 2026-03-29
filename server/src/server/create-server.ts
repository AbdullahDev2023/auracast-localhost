import { randomUUID } from 'node:crypto';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { resolve } from 'node:path';
import { WebSocketServer } from 'ws';
import type WebSocket from 'ws';
import { loadEnvConfig, type EnvConfig } from '../core/env';
import { applyCors, sendJson, sendText } from '../core/http';
import { LoggerFactory } from '../core/logger';
import type {
    HttpRouteDefinition,
    RequestContext,
    ServerContext,
    ServerModule,
    ServerRuntimeContext,
    WebSocketContext,
    WsRouteDefinition,
} from '../core/contracts';
import { createDashboardModule } from '../modules/dashboard-module';
import { createOperationsModule } from '../modules/operations-module';
import { createRelayModule } from '../modules/relay-module';
import { createUdpRelayModule } from '../modules/udp-relay-module';
import { createAudioCastModule } from '../modules/audio-cast-module';
import { ChannelRegistry } from '../services/channel-registry';
import { FirebaseCommandService } from '../services/firebase-command-service';
import { MetricsService } from '../services/metrics-service';
import { RateLimitService } from '../services/rate-limit-service';
import { AudioStoreService } from '../services/audio-store-service';
import type { AuraCastServiceOverrides } from '../services/types';

export interface CreateAuraCastServerOptions {
    projectRoot?: string;
    config?: Partial<EnvConfig>;
    modules?: ServerModule[];
    serviceOverrides?: AuraCastServiceOverrides;
}

export interface AuraCastServer {
    readonly context: ServerContext;
    readonly httpServer: ReturnType<typeof createServer>;
    readonly webSocketServer: WebSocketServer;
    start: () => Promise<number>;
    stop: () => Promise<void>;
}

function createRequestContext(request: IncomingMessage, loggerFactory: LoggerFactory): RequestContext {
    const requestId = request.headers['x-request-id']?.toString() || randomUUID().slice(0, 8);
    const url = new URL(request.url || '/', 'http://localhost');
    return {
        requestId,
        requestLogger: loggerFactory.forModule('HTTP').withFields({
            requestId,
            method: request.method || 'GET',
            path: url.pathname,
        }),
        startTimeMs: Date.now(),
        url,
    };
}

function createWebSocketContext(request: IncomingMessage, loggerFactory: LoggerFactory): WebSocketContext {
    const requestId = request.headers['x-request-id']?.toString() || randomUUID().slice(0, 8);
    const url = new URL(request.url || '/', 'http://localhost');
    return {
        requestLogger: loggerFactory.forModule('WS').withFields({
            requestId,
            path: url.pathname,
            streamId: url.searchParams.get('id')?.slice(0, 8),
        }),
        url,
    };
}

export function createAuraCastServer(options: CreateAuraCastServerOptions = {}): AuraCastServer {
    const projectRoot = options.projectRoot || resolve(__dirname, '..', '..');
    const config: EnvConfig = {
        ...loadEnvConfig(projectRoot),
        ...options.config,
    };
    const loggerFactory = new LoggerFactory(config.logLevel, config.logFormat);
    const bootLogger = loggerFactory.forModule('Server');

    // Create the UDP relay module first so its service can be injected into `services`.
    const udpRelayModule = createUdpRelayModule();

    const services = {
        channels: new ChannelRegistry(config.channelGraceMs),
        firebase: options.serviceOverrides?.firebase || new FirebaseCommandService(config, loggerFactory.forModule('Firebase')),
        metrics: new MetricsService(),
        rateLimit: new RateLimitService(
            config.rateLimitEnabled,
            config.rateWindowMs,
            config.maxRequestsPerWindow,
            loggerFactory.forModule('RateLimit'),
        ),
        udpRelay: udpRelayModule.service,
        audioStore: new AudioStoreService(resolve(projectRoot, config.audioCastStoreDir)),
    };

    const context: ServerContext = {
        config,
        loggerFactory,
        services,
        paths: {
            projectRoot,
            publicDir: resolve(projectRoot, 'public'),
        },
        registeredHealthChecks: [],
    };

    const modules = options.modules || [
        createDashboardModule(),
        udpRelayModule,
        createRelayModule(),
        createOperationsModule(),
        createAudioCastModule(),
    ];

    const httpRoutes = new Map<string, HttpRouteDefinition>();
    const wsRoutes = new Map<string, WsRouteDefinition>();
    const startHooks: Array<(runtimeContext: ServerRuntimeContext) => Promise<void> | void> = [];
    const stopHooks: Array<(runtimeContext: ServerRuntimeContext) => Promise<void> | void> = [];

    for (const module of modules) {
        const registration = module.register(context);
        if (registration instanceof Promise) {
            throw new Error(`Async module registration is not supported for ${module.name}`);
        }

        for (const route of registration.httpRoutes || []) {
            httpRoutes.set(`${route.method}:${route.path}`, route);
        }
        for (const route of registration.wsRoutes || []) {
            wsRoutes.set(route.path, route);
        }
        context.registeredHealthChecks?.push(...(registration.healthChecks || []));
        if (registration.onStart) {
            startHooks.push(registration.onStart);
        }
        if (registration.onStop) {
            stopHooks.unshift(registration.onStop);
        }
    }

    const httpServer = createServer((request: IncomingMessage, response: ServerResponse) => {
        void (async () => {
        const requestContext = createRequestContext(request, loggerFactory);
        response.setHeader('X-Request-Id', requestContext.requestId);
        applyCors(response);

        response.on('finish', () => {
            requestContext.requestLogger.info('Request complete', {
                statusCode: response.statusCode,
                durationMs: Date.now() - requestContext.startTimeMs,
            });
        });

        services.metrics.increment('httpRequests');

        if (request.method === 'OPTIONS') {
            response.writeHead(204);
            response.end();
            return;
        }

        if (services.rateLimit.check(request, response)) {
            services.metrics.increment('rateLimitHits');
            return;
        }

        const route = httpRoutes.get(`${request.method}:${requestContext.url.pathname}`)
            ?? (requestContext.url.pathname.startsWith('/audio/')
                ? httpRoutes.get(`GET:/audio/`)
                : undefined);
        if (!route) {
            sendText(response, 404, 'Not found');
            return;
        }

        try {
            await route.handler(request, response, requestContext, context);
            services.metrics.gauge('activeChannels', services.channels.size);
            services.metrics.gauge('activeBrowsers', services.channels.totals().totalBrowsers);
        } catch (error) {
            services.metrics.increment('errors');
            requestContext.requestLogger.error('Unhandled HTTP route error', {
                error: error instanceof Error ? error.message : String(error),
            });
            if (!response.headersSent) {
                sendJson(response, 500, { error: 'internal_error', requestId: requestContext.requestId });
            } else if (!response.writableEnded) {
                response.end();
            }
        }
        })();
    });

    const webSocketServer = new WebSocketServer({ server: httpServer });
    webSocketServer.on('connection', (socket: WebSocket, request: IncomingMessage) => {
        const wsContext = createWebSocketContext(request, loggerFactory);
        const route = wsRoutes.get(wsContext.url.pathname);

        services.metrics.increment('wsConnections');

        if (!route) {
            socket.close(1008, 'Unknown path');
            return;
        }

        try {
            route.handler(socket, request, wsContext, context);
            services.metrics.gauge('activeChannels', services.channels.size);
            services.metrics.gauge('activeBrowsers', services.channels.totals().totalBrowsers);
        } catch (error) {
            services.metrics.increment('errors');
            wsContext.requestLogger.error('Unhandled WebSocket route error', {
                error: error instanceof Error ? error.message : String(error),
            });
            socket.close(1011, 'Server error');
        }
    });

    const runtimeContext: ServerRuntimeContext = {
        ...context,
        httpServer,
        webSocketServer,
    };

    return {
        context,
        httpServer,
        webSocketServer,
        async start() {
            await new Promise<void>((resolveStart, rejectStart) => {
                httpServer.once('error', rejectStart);
                httpServer.listen(config.port, () => {
                    httpServer.off('error', rejectStart);
                    resolveStart();
                });
            });

            for (const hook of startHooks) {
                await hook(runtimeContext);
            }

            const address = httpServer.address();
            const port = typeof address === 'object' && address ? address.port : config.port;

            bootLogger.info('AuraCast server ready', {
                port,
                udpPort: config.udpPort,
                dashboard: `http://localhost:${port}`,
                streams: `http://localhost:${port}/streams`,
            });
            return port;
        },
        async stop() {
            for (const hook of stopHooks) {
                await hook(runtimeContext);
            }

            await new Promise<void>(resolveStop => {
                webSocketServer.close(() => resolveStop());
                for (const client of webSocketServer.clients) {
                    client.terminate();
                }
            });

            await new Promise<void>((resolveStop, rejectStop) => {
                httpServer.close(error => {
                    if (error) {
                        rejectStop(error);
                        return;
                    }
                    resolveStop();
                });
            });

            await services.firebase.dispose();
        },
    };
}
