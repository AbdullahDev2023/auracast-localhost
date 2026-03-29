import type { IncomingMessage, Server, ServerResponse } from 'node:http';
import type * as dgram from 'node:dgram';
import type { WebSocketServer, WebSocket } from 'ws';
import type { EnvConfig } from './env';
import type { LoggerFactory, ScopedLogger } from './logger';
import type { AuraCastServices } from '../services/types';

export interface RequestContext {
    requestId: string;
    requestLogger: ScopedLogger;
    startTimeMs: number;
    url: URL;
}

export interface WebSocketContext {
    requestLogger: ScopedLogger;
    url: URL;
}

export interface ServerContext {
    config: EnvConfig;
    loggerFactory: LoggerFactory;
    services: AuraCastServices;
    paths: {
        projectRoot: string;
        publicDir: string;
    };
    registeredHealthChecks?: HealthCheck[];
}

export interface ServerRuntimeContext extends ServerContext {
    httpServer: Server;
    webSocketServer: WebSocketServer;
    /** UDP relay socket — set by UdpRelayModule.onStart, undefined until then. */
    udpServer?: dgram.Socket;
}

export interface HttpRouteDefinition {
    method: 'GET' | 'POST';
    path: string;
    description: string;
    handler: (
        request: IncomingMessage,
        response: ServerResponse,
        context: RequestContext,
        serverContext: ServerContext,
    ) => Promise<void> | void;
}

export interface WsRouteDefinition {
    path: string;
    description: string;
    handler: (
        socket: WebSocket,
        request: IncomingMessage,
        context: WebSocketContext,
        serverContext: ServerContext,
    ) => void;
}

export interface HealthCheckResult {
    ok: boolean;
    details?: unknown;
}

export interface HealthCheck {
    name: string;
    critical: boolean;
    check: (serverContext: ServerContext) => Promise<HealthCheckResult> | HealthCheckResult;
}

export interface ServerModuleRegistration {
    httpRoutes?: HttpRouteDefinition[];
    wsRoutes?: WsRouteDefinition[];
    healthChecks?: HealthCheck[];
    onStart?: (runtimeContext: ServerRuntimeContext) => Promise<void> | void;
    onStop?: (runtimeContext: ServerRuntimeContext) => Promise<void> | void;
}

export interface ServerModule {
    name: string;
    register: (serverContext: ServerContext) => Promise<ServerModuleRegistration> | ServerModuleRegistration;
}

export interface HealthSummary {
    status: 'ready' | 'degraded';
    checks: Array<{
        name: string;
        critical: boolean;
        ok: boolean;
        details?: unknown;
    }>;
}
