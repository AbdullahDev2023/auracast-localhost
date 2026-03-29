import WebSocket from 'ws';

export interface QualityConfig {
    bitrate:    number;  // bps  e.g. 16_000 | 32_000 | 64_000 | 128_000 | 192_000
    sampleRate: number;  // Hz   fixed at 48_000 — not configurable
    frameMs:    number;  // ms   20 | 40 | 60
    complexity: number;  // 0–10
}

export interface ChannelStats {
    framesRelayed: number;
    bytesRelayed: number;
    connectedAt: number | null;
    disconnectedAt: number | null;
    lastActivityMs: number;
}

export interface Channel {
    id: string;
    displayName: string;
    controlWs: WebSocket | null;
    phoneWs: WebSocket | null;
    browsers: Set<WebSocket>;
    codecConfig: Record<string, unknown> | null;
    streamingActive: boolean;
    qualityConfig: QualityConfig | null;
    audioMode: string;
    cleanupTimer: NodeJS.Timeout | null;
    stats: ChannelStats;
}

export interface ChannelSummary {
    id: string;
    displayName: string;
    phoneConnected: boolean;
    streamingActive: boolean;
    audioMode: string;
    qualityConfig: QualityConfig | null;
    listeners: number;
    framesRelayed: number;
    kbRelayed: string;
    connectedAt: number | null;
    disconnectedAt: number | null;
}

export interface ChannelTotals {
    livePhones: number;
    streaming: number;
    totalBrowsers: number;
    totalFrames: number;
    totalBytes: number;
}

export class ChannelRegistry {
    private readonly channels = new Map<string, Channel>();

    constructor(private readonly channelGraceMs: number) {}

    get size(): number {
        return this.channels.size;
    }

    values(): IterableIterator<Channel> {
        return this.channels.values();
    }

    getChannel(id: string): Channel | undefined {
        return this.channels.get(id);
    }

    getOrCreateChannel(id: string, displayName?: string): Channel {
        const existing = this.channels.get(id);
        if (existing) {
            if (displayName) {
                existing.displayName = displayName;
            }
            return existing;
        }

        const channel: Channel = {
            id,
            displayName: displayName || id.slice(0, 8),
            controlWs: null,
            phoneWs: null,
            browsers: new Set(),
            codecConfig: null,
            streamingActive: false,
            qualityConfig: null,
            audioMode: 'mic',
            cleanupTimer: null,
            stats: {
                framesRelayed: 0,
                bytesRelayed: 0,
                connectedAt: null,
                disconnectedAt: null,
                lastActivityMs: 0,
            },
        };

        this.channels.set(id, channel);
        return channel;
    }

    cancelGrace(id: string): void {
        const channel = this.channels.get(id);
        if (!channel?.cleanupTimer) {
            return;
        }
        clearTimeout(channel.cleanupTimer);
        channel.cleanupTimer = null;
    }

    scheduleGrace(id: string, onExpired?: (channelId: string) => void): void {
        const channel = this.channels.get(id);
        if (!channel) {
            return;
        }

        if (channel.cleanupTimer) {
            clearTimeout(channel.cleanupTimer);
        }

        channel.cleanupTimer = setTimeout(() => {
            const current = this.channels.get(id);
            if (!current) {
                return;
            }
            if (current.phoneWs || current.controlWs || current.browsers.size > 0) {
                return;
            }
            this.channels.delete(id);
            onExpired?.(id);
        }, this.channelGraceMs);
    }

    broadcast(channel: Channel, payload: string | Buffer, options?: { binary?: boolean }): void {
        for (const browser of channel.browsers) {
            if (browser.readyState !== WebSocket.OPEN) {
                continue;
            }
            if (options) {
                browser.send(payload, options);
            } else {
                browser.send(payload);
            }
        }
    }

    isPhoneConnected(channel: Channel): boolean {
        return Boolean(channel.controlWs || channel.phoneWs);
    }

    toSummaryList(): ChannelSummary[] {
        return Array.from(this.channels.values()).map(channel => ({
            id: channel.id,
            displayName: channel.displayName,
            phoneConnected: this.isPhoneConnected(channel),
            streamingActive: channel.streamingActive,
            audioMode: channel.audioMode,
            qualityConfig: channel.qualityConfig,
            listeners: channel.browsers.size,
            framesRelayed: channel.stats.framesRelayed,
            kbRelayed: (channel.stats.bytesRelayed / 1024).toFixed(1),
            connectedAt: channel.stats.connectedAt,
            disconnectedAt: channel.stats.disconnectedAt,
        }));
    }

    totals(): ChannelTotals {
        let livePhones = 0;
        let streaming = 0;
        let totalBrowsers = 0;
        let totalFrames = 0;
        let totalBytes = 0;

        for (const channel of this.channels.values()) {
            if (this.isPhoneConnected(channel)) {
                livePhones += 1;
            }
            if (channel.streamingActive) {
                streaming += 1;
            }
            totalBrowsers += channel.browsers.size;
            totalFrames += channel.stats.framesRelayed;
            totalBytes += channel.stats.bytesRelayed;
        }

        return {
            livePhones,
            streaming,
            totalBrowsers,
            totalFrames,
            totalBytes,
        };
    }
}
