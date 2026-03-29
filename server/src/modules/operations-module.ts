import { sendJson } from '../core/http';
import type { HealthSummary, ServerContext, ServerModule } from '../core/contracts';

async function buildHealthSummary(serverContext: ServerContext): Promise<HealthSummary> {
    const checks = [];
    let degraded = false;

    for (const definition of serverContext.registeredHealthChecks || []) {
        const result = await definition.check(serverContext);
        if (definition.critical && !result.ok) {
            degraded = true;
        }
        checks.push({
            name: definition.name,
            critical: definition.critical,
            ok: result.ok,
            details: result.details,
        });
    }

    return {
        status: degraded ? 'degraded' : 'ready',
        checks,
    };
}

export function createOperationsModule(): ServerModule {
    return {
        name: 'operations',
        register(serverContext) {
            const logger = serverContext.loggerFactory.forModule('Ops');

            return {
                httpRoutes: [
                    {
                        method: 'GET',
                        path: '/health',
                        description: 'Return backward-compatible health summary',
                        handler(_request, response) {
                            const totals = serverContext.services.channels.totals();
                            const metricsSnapshot = serverContext.services.metrics.snapshot();
                            sendJson(response, 200, {
                                status: 'ok',
                                version: '6.0',
                                uptimeSeconds: metricsSnapshot.uptimeSec,
                                totalChannels: serverContext.services.channels.size,
                                livePhones: totals.livePhones,
                                streaming: totals.streaming,
                                totalBrowsers: totals.totalBrowsers,
                                totalFrames: totals.totalFrames,
                                kbRelayed: (totals.totalBytes / 1024).toFixed(1),
                            });
                        },
                    },
                    {
                        method: 'GET',
                        path: '/ready',
                        description: 'Return readiness checks for container health probes',
                        async handler(_request, response, _context, currentServerContext) {
                            const summary = await buildHealthSummary(currentServerContext);
                            sendJson(response, summary.status === 'ready' ? 200 : 503, summary);
                        },
                    },
                    {
                        method: 'GET',
                        path: '/metrics',
                        description: 'Expose Prometheus metrics',
                        handler(_request, response) {
                            response.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4; charset=utf-8' });
                            response.end(serverContext.services.metrics.toPrometheus());
                        },
                    },
                    {
                        method: 'POST',
                        path: '/wake',
                        description: 'Queue a reconnect command through Firebase Admin',
                        async handler(_request, response, context) {
                            if (!serverContext.services.firebase.adminReady) {
                                sendJson(response, 503, {
                                    error: 'Firebase Admin not configured',
                                    hint: 'set FIREBASE_SERVICE_ACCOUNT_PATH or FIREBASE_SERVICE_ACCOUNT_JSON and restart',
                                });
                                return;
                            }

                            const streamId = context.url.searchParams.get('id');
                            if (!streamId) {
                                sendJson(response, 400, { error: 'missing ?id=' });
                                return;
                            }

                            try {
                                const { commandId } = await serverContext.services.firebase.sendWake(streamId);
                                logger.info('/wake sent', { streamId: streamId.slice(0, 8), commandId: commandId.slice(0, 8) });
                                sendJson(response, 200, {
                                    ok: true,
                                    commandId,
                                    action: 'reconnect',
                                    channel: 'firebase-admin',
                                });
                            } catch (error) {
                                logger.error('/wake failed', {
                                    streamId: streamId.slice(0, 8),
                                    error: error instanceof Error ? error.message : String(error),
                                });
                                sendJson(response, 500, {
                                    error: error instanceof Error ? error.message : String(error),
                                });
                            }
                        },
                    },
                ],
                healthChecks: [
                    {
                        name: 'firebase-admin',
                        critical: false,
                        check(contextForCheck) {
                            const snapshot = contextForCheck.services.firebase.getHealthSnapshot();
                            return {
                                ok: snapshot.adminReady || !snapshot.adminConfigured,
                                details: snapshot,
                            };
                        },
                    },
                    {
                        name: 'metrics-service',
                        critical: true,
                        check(contextForCheck) {
                            return {
                                ok: true,
                                details: contextForCheck.services.metrics.snapshot(),
                            };
                        },
                    },
                ],
            };
        },
    };
}
