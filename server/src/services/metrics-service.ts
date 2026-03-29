export interface MetricsSnapshot {
    uptimeSec: number;
    counters: Record<string, number>;
    gauges: Record<string, number>;
}

export class MetricsService {
    private readonly startedAt = Date.now();
    private readonly counters: Record<string, number> = {
        httpRequests: 0,
        wsConnections: 0,
        audioFrames: 0,
        commandsSent: 0,
        rateLimitHits: 0,
        errors: 0,
    };

    private readonly gauges: Record<string, number> = {
        activeChannels: 0,
        activeBrowsers: 0,
    };

    increment(name: keyof MetricsService['counters'], amount = 1): void {
        this.counters[name] = (this.counters[name] ?? 0) + amount;
    }

    gauge(name: keyof MetricsService['gauges'], value: number): void {
        this.gauges[name] = value;
    }

    snapshot(): MetricsSnapshot {
        return {
            uptimeSec: Math.floor((Date.now() - this.startedAt) / 1000),
            counters: { ...this.counters },
            gauges: { ...this.gauges },
        };
    }

    toPrometheus(): string {
        const snapshot = this.snapshot();
        const lines = [
            '# HELP auracast_uptime_seconds Server uptime in seconds',
            '# TYPE auracast_uptime_seconds gauge',
            `auracast_uptime_seconds ${snapshot.uptimeSec}`,
        ];

        for (const [name, value] of Object.entries(snapshot.counters)) {
            lines.push(`# TYPE auracast_${name}_total counter`);
            lines.push(`auracast_${name}_total ${value}`);
        }

        for (const [name, value] of Object.entries(snapshot.gauges)) {
            lines.push(`# TYPE auracast_${name} gauge`);
            lines.push(`auracast_${name} ${value}`);
        }

        return `${lines.join('\n')}\n`;
    }
}
