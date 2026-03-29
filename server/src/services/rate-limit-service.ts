import type { IncomingMessage, ServerResponse } from 'node:http';
import type { ScopedLogger } from '../core/logger';

export class RateLimitService {
    private readonly windows = new Map<string, number[]>();

    constructor(
        private readonly enabled: boolean,
        private readonly windowMs: number,
        private readonly maxRequestsPerWindow: number,
        private readonly logger: ScopedLogger,
    ) {}

    check(request: IncomingMessage, response: ServerResponse): boolean {
        if (!this.enabled) {
            return false;
        }

        const ipAddress = request.socket.remoteAddress || 'unknown';
        const now = Date.now();
        const windowStart = now - this.windowMs;
        const recentRequests = (this.windows.get(ipAddress) || []).filter(timestamp => timestamp > windowStart);

        if (recentRequests.length >= this.maxRequestsPerWindow) {
            this.logger.warn('Rate limit hit', { ipAddress, count: recentRequests.length });
            response.writeHead(429, {
                'Content-Type': 'application/json; charset=utf-8',
                'Retry-After': Math.ceil(this.windowMs / 1000),
            });
            response.end(JSON.stringify({
                error: 'Too many requests',
                retryAfter: Math.ceil(this.windowMs / 1000),
            }));
            return true;
        }

        recentRequests.push(now);
        this.windows.set(ipAddress, recentRequests);
        return false;
    }

    purgeStale(): void {
        const cutoff = Date.now() - this.windowMs;
        for (const [ipAddress, timestamps] of this.windows.entries()) {
            const freshTimestamps = timestamps.filter(timestamp => timestamp > cutoff);
            if (freshTimestamps.length === 0) {
                this.windows.delete(ipAddress);
                continue;
            }
            this.windows.set(ipAddress, freshTimestamps);
        }
    }
}
